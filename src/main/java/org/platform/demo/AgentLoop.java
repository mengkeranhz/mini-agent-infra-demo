package org.platform.demo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.platform.kernel.JournalEvent;
import org.platform.kernel.Receipt;
import org.platform.kernel.Uncertain;
import org.platform.kernel.UncertainStepException;
import org.platform.kernel.id.IdempotencyKey;
import org.platform.kernel.id.RunId;
import org.platform.kernel.id.StepCtx;
import org.platform.kernel.id.StepId;
import org.platform.kernel.id.TenantId;
import org.platform.kernel.port.HarnessAdapter;
import org.platform.kernel.port.Journal;
import org.platform.kernel.port.ModelGateway;
import org.platform.kernel.port.ReceiptStore;
import org.platform.kernel.port.ToolGateway;

/**
 * demo harness：最小 agent 循环——采样、执行工具、写回 messages 与 journal，直到模型不再要工具。
 * 网关、journal、收据仓全部构造器注入，本类不 new 任何基础设施。
 * run() 先查 journal：同 runId 已有进度就续跑——重建轨迹、裁决悬空步、步号接着分；跑完的 run 直接返回既有答案。
 * 隔离：构造时注入的 tenant 写进每条事件，重放只经 journal.scan(tenant, runId)——
 * runId 只定位 run 不定位租户，猜中别家的 runId 也只会查到一个空 run。
 * 轨迹与重试计数是 run 的私有状态，收在 run() 局部变量里往下传——放实例字段，
 * 同一实例跑第二个 run 时会把第一个 run 的内容串进去。
 * assistant 中间文本不在事件里，重建轨迹时缺这块（已知口子，当前桩模型不受影响）。
 */
public final class AgentLoop implements HarnessAdapter {

    private final TenantId tenant;
    private final ToolGateway tools;
    private final ModelGateway model;
    private final Journal journal;
    private final ReceiptStore receipts;

    /** 同一步连续发起这么多回仍悬空，就不再自动重跑——多半是环境出了系统性问题，变成人的事。 */
    private static final int MAX_ATTEMPTS = 3;

    /**
     * 构造器注入——谁造的不关我事，我只管用。
     * 契约：receipts 必须与工具网关（IdempotentToolWrapper）里的是同一个实例——
     * 恢复裁决查的收据就是幂等层存的收据，各拿一个仓等于没有收据。
     */
    public AgentLoop(TenantId tenant, ToolGateway tools, ModelGateway model, Journal journal, ReceiptStore receipts) {
        this.tenant = tenant;
        this.tools = tools;
        this.model = model;
        this.journal = journal;
        this.receipts = receipts;
    }

    @Override
    public String run(RunId runId, String task) {
        Replay prior = replay(runId);
        if (prior.finished()) {
            return prior.answer();
        }
        // 只在内存里，进程死了就没了；恢复时轨迹从 journal 重建，不依赖它
        List<Msg> messages = new ArrayList<>();
        // 本进程内每个工具调用（按 toolUseId）已发起的次数；重启时从 journal 的 Dispatched.attempt 归纳回来
        Map<String, Integer> attempts = new HashMap<>();
        if (prior.started()) {
            resumeFrom(messages, attempts, runId, prior);
        } else {
            beginRun(messages, runId, task);
        }
        StepId stepId = new StepId(runId.value(), prior.lastDoneSeq() + 1);
        while (true) {
            ModelResponse resp = model.sample(new StepCtx(tenant, runId, stepId), messages);
            messages.add(Msg.assistant(resp.text()));
            if (!resp.hasToolUse()) {
                return finish(runId, resp.text());
            }
            for (ToolUse call : resp.toolUses()) {
                stepId = executeCall(runId, stepId, call, messages, attempts);
            }
        }
    }

    /** 全新 run：记下起点事件，任务进轨迹。 */
    private void beginRun(List<Msg> messages, RunId runId, String task) {
        journal.append(new JournalEvent.RunStarted(tenant, runId, task));
        messages.add(Msg.user(task));
    }

    /** 续跑：从 journal 重建轨迹，再裁决上次崩溃留下的悬空步。 */
    private void resumeFrom(List<Msg> messages, Map<String, Integer> attempts, RunId runId, Replay prior) {
        messages.addAll(prior.messages());
        attempts.putAll(prior.attemptByToolUse());
        adjudicate(runId, messages, prior.uncertain(), prior.adjudicatedToolUse());
    }

    /** 模型不再要工具：run 收尾，答案落盘后返回。 */
    private String finish(RunId runId, String text) {
        journal.append(new JournalEvent.RunFinished(tenant, runId, text));
        return text;
    }

    /** 一次工具调用的完整生命周期：Planned → Dispatched → invoke → Done/Failed；返回推进后的步号。 */
    private StepId executeCall(RunId runId, StepId stepId, ToolUse call, List<Msg> messages,
            Map<String, Integer> attempts) {
        int attempt = attempts.merge(call.id(), 1, Integer::sum);
        // 计划段先落盘：toolName / inputDigest / idempotent，恢复裁决全靠这三个字段
        journal.append(new JournalEvent.StepPlanned(tenant, stepId, call.id(), call.name(),
                call.inputDigest(), tools.idempotent(call.name())));
        // 发起段必须在真正调用之前落盘（write-ahead）：
        // 崩溃若发生在这之后、Done 之前，恢复时只能按「可能已执行」处理，不敢直接重发
        journal.append(new JournalEvent.StepDispatched(tenant, stepId, call.id(), attempt));
        ToolResult result = tools.invoke(new StepCtx(tenant, runId, stepId), call);
        if (result.isError()) {
            journal.append(new JournalEvent.StepFailed(tenant, stepId, call.id(), result.content()));
        } else {
            journal.append(new JournalEvent.StepDone(tenant, stepId, result.toolUseId(), result.content(),
                    JournalEvent.SOURCE_EXECUTED));
        }
        messages.add(Msg.tool(result.toolUseId(), result.content()));
        return stepId.next();
    }

    /**
     * 恢复裁决：处理上次崩溃留下的「说不清执行没执行」的工具调用（悬空步）。
     *
     * 为什么麻烦：进程可能死在「已声明发起、结果未落盘」之间——副作用可能已经发生，
     * 也可能压根没来得及执行，journal 无法区分这两种情况。
     *
     * 先全量过一遍，处理不了的攒起来一次挂起——撞上第一个等人的就 throw，
     * N 个悬空步就得人工来 N 趟，每趟只裁决最先撞上的那一个。
     *
     * 分支，按风险从低到高：
     * 1. 幂等 + 未到重试上限 → 重跑无害，交给主循环重新走全流程；
     * 2. 收据在（含到上限的幂等步）→ 不再调用工具，用收据内容直接补一条 Done；
     * 3. 无法证明结果 → 落一条 StepResolution（awaiting_human）记下原因，打包抛异常挂起本 run：
     *    非幂等无收据，或幂等但连续 MAX_ATTEMPTS 次仍悬空（环境多半出了系统性问题）。
     *
     * 第 3 分支的 StepResolution 不能省：判定本身必须落盘——否则每次重启都重新发现
     * 这个悬空步、重新挂起，journal 却看不出它已进入人工流程，永远收敛不了。
     * 落了之后 replay() 会记住「已裁定」：收据没回来之前再重启，直接重抛异常、
     * 不再写第二条裁定——否则 M2 的事件订阅、M5 的审计会为每次重启各开一张工单。
     */
    private void adjudicate(RunId runId, List<Msg> messages, List<Uncertain> uncertain,
            Set<String> adjudicatedToolUse) {
        List<Uncertain> awaiting = new ArrayList<>();
        for (Uncertain u : uncertain) {
            if (u.idempotent() && u.attempt() < MAX_ATTEMPTS) {
                continue;
            }
            Receipt receipt = receipts.find(new IdempotencyKey(tenant, runId, u.toolUseId())).orElse(null);
            if (receipt != null) {
                journal.append(new JournalEvent.StepDone(tenant, u.stepId(), u.toolUseId(), receipt.content(),
                        JournalEvent.SOURCE_REPLAYED_FROM_RECEIPT));
                messages.add(Msg.tool(u.toolUseId(), receipt.content()));
                continue;
            }
            awaiting.add(u);
        }
        if (awaiting.isEmpty()) {
            return;
        }
        for (Uncertain u : awaiting) {
            if (adjudicatedToolUse.contains(u.toolUseId())) {
                continue; // 已裁定过等人、收据也还没回来：静默重抛，不再堆第二条裁定
            }
            journal.append(new JournalEvent.StepResolution(tenant, u.stepId(), u.toolUseId(),
                    "awaiting_human", reasonOf(u), null));
        }
        throw new UncertainStepException(awaiting, this::reasonOf);
    }

    /** 挂起理由：非幂等无收据说不清结果；幂等重跑到上限说明环境出了系统性问题。 */
    private String reasonOf(Uncertain u) {
        return u.idempotent()
                ? "重试 " + MAX_ATTEMPTS + " 次仍无结果"
                : "结果未知且无收据，不可自动重试";
    }

    /** 从 journal 重放 (tenant, runId) 名下的既有进度：跑没跑过、跑到哪、轨迹长什么样、哪些步悬空。 */
    private Replay replay(RunId runId) {
        List<Msg> rebuilt = new ArrayList<>();
        // 身份一律按 toolUseId 对齐，seq 不当 join key——真 LLM 重放序列会漂移，按 seq 对不上
        Map<String, JournalEvent.StepPlanned> plannedByToolUse = new HashMap<>();
        Map<String, Integer> attemptByToolUse = new HashMap<>();
        Set<String> resolvedToolUse = new HashSet<>();
        Set<String> adjudicatedToolUse = new HashSet<>();
        int lastDoneSeq = -1;
        boolean started = false;
        boolean finished = false;
        String answer = null;
        String task = null;
        // 事件源已按 (tenant, runId) 收敛（端口契约），这里不再二次过滤：别家的事件进不来，也判不出别家的进度
        for (JournalEvent event : journal.scan(tenant, runId, -1L)) {
            switch (event) {
                case JournalEvent.RunStarted ev -> {
                    started = true;
                    task = ev.task();
                }
                case JournalEvent.StepPlanned ev -> plannedByToolUse.put(ev.toolUseId(), ev);
                case JournalEvent.StepDispatched ev -> attemptByToolUse.merge(ev.toolUseId(), ev.attempt(), Math::max);
                case JournalEvent.StepDone ev -> {
                    rebuilt.add(Msg.tool(ev.toolUseId(), ev.content()));
                    resolvedToolUse.add(ev.toolUseId());
                    lastDoneSeq = Math.max(lastDoneSeq, ev.stepId().seq());
                }
                case JournalEvent.StepFailed ev -> {
                    rebuilt.add(Msg.tool(ev.toolUseId(), ev.error()));
                    resolvedToolUse.add(ev.toolUseId());
                    lastDoneSeq = Math.max(lastDoneSeq, ev.stepId().seq());
                }
                case JournalEvent.RunFinished ev -> {
                    finished = true;
                    answer = ev.answer();
                }
                case JournalEvent.StepResolution ev -> adjudicatedToolUse.add(ev.toolUseId());
            }
        }
        if (started) {
            rebuilt.add(0, Msg.user(task));
        }
        return new Replay(started, finished, answer, lastDoneSeq,
                rebuilt, uncertainOf(plannedByToolUse, attemptByToolUse, resolvedToolUse),
                adjudicatedToolUse, attemptByToolUse);
    }

    /** 悬空步 = 已派发（Dispatched）但没有任何终态（Done/Failed）的调用，配上计划段的元数据。 */
    private static List<Uncertain> uncertainOf(Map<String, JournalEvent.StepPlanned> plannedByToolUse,
            Map<String, Integer> attemptByToolUse, Set<String> resolvedToolUse) {
        List<Uncertain> uncertain = new ArrayList<>();
        for (String toolUseId : attemptByToolUse.keySet()) {
            if (!resolvedToolUse.contains(toolUseId) && plannedByToolUse.containsKey(toolUseId)) {
                JournalEvent.StepPlanned planned = plannedByToolUse.get(toolUseId);
                uncertain.add(new Uncertain(planned.tenantId(), planned.runId(), planned.stepId(),
                        planned.toolUseId(), planned.toolName(), planned.idempotent(),
                        attemptByToolUse.get(toolUseId), planned.inputDigest()));
            }
        }
        // keySet() 出自 HashMap，无序——多个悬空步时裁决顺序、按收据回放补消息的顺序都会随 hash 漂移，
        // 恢复必须可复现：按计划段 seq 排（不同 toolUseId 首次派发的 seq 必不相同，是全序）
        uncertain.sort(Comparator.comparingInt(u -> u.stepId().seq()));
        return uncertain;
    }
}
