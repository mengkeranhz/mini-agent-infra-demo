package org.platform.demo;

import java.util.ArrayList;
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
 * assistant 中间文本不在事件里，重建轨迹时缺这块（已知口子，当前桩模型不受影响）。
 */
public final class AgentLoop implements HarnessAdapter {

    private final TenantId tenant;
    private final ToolGateway tools;
    private final ModelGateway model;
    private final Journal journal;
    private final ReceiptStore receipts;

    /** 只在内存里，进程死了就没了；恢复时轨迹从 journal 重建，不依赖它。 */
    private final List<Msg> messages = new ArrayList<>();

    /** 本进程内每个 seq 已发起的次数；重启时从 journal 的 Dispatched.attempt 归纳回来。 */
    private final Map<Integer, Integer> attempts = new HashMap<>();

    /** 同一步连续发起这么多回仍悬空，就不再自动重跑——多半是环境出了系统性问题，变成人的事。 */
    private static final int MAX_ATTEMPTS = 3;

    /** 构造器注入——谁造的不关我事，我只管用。 */
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
        if (prior.started()) {
            resumeFrom(runId, prior);
        } else {
            beginRun(runId, task);
        }
        StepId stepId = new StepId(runId.value(), prior.lastDoneSeq() + 1);
        while (true) {
            ModelResponse resp = model.sample(new StepCtx(tenant, runId, stepId), messages);
            messages.add(Msg.assistant(resp.text()));
            if (!resp.hasToolUse()) {
                return finish(runId, resp.text());
            }
            for (ToolUse call : resp.toolUses()) {
                stepId = executeCall(runId, stepId, call);
            }
        }
    }

    /** 全新 run：记下起点事件，任务进轨迹。 */
    private void beginRun(RunId runId, String task) {
        journal.append(new JournalEvent.RunStarted(runId, task));
        messages.add(Msg.user(task));
    }

    /** 续跑：从 journal 重建轨迹，再裁决上次崩溃留下的悬空步。 */
    private void resumeFrom(RunId runId, Replay prior) {
        messages.addAll(prior.messages());
        attempts.putAll(prior.maxAttempt());
        adjudicate(runId, prior.uncertain(), prior.adjudicated());
    }

    /** 模型不再要工具：run 收尾，答案落盘后返回。 */
    private String finish(RunId runId, String text) {
        journal.append(new JournalEvent.RunFinished(runId, text));
        return text;
    }

    /** 一次工具调用的完整生命周期：Planned → Dispatched → invoke → Done/Failed；返回推进后的步号。 */
    private StepId executeCall(RunId runId, StepId stepId, ToolUse call) {
        int attempt = attempts.merge(stepId.seq(), 1, Integer::sum);
        // 计划段先落盘：toolName / inputDigest / idempotent，恢复裁决全靠这三个字段
        journal.append(new JournalEvent.StepPlanned(stepId, call.id(), call.name(),
                call.inputDigest(), tools.idempotent(call.name())));
        // 发起段必须在真正调用之前落盘（write-ahead）：
        // 崩溃若发生在这之后、Done 之前，恢复时只能按「可能已执行」处理，不敢直接重发
        journal.append(new JournalEvent.StepDispatched(stepId, call.id(), attempt));
        ToolResult result = tools.invoke(new StepCtx(tenant, runId, stepId), call);
        if (result.isError()) {
            journal.append(new JournalEvent.StepFailed(stepId, call.id(), result.content()));
        } else {
            journal.append(new JournalEvent.StepDone(stepId, result.toolUseId(), result.content()));
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
     * 分支，按风险从低到高：
     * 1. 幂等 + 未到重试上限 → 重跑无害，交给主循环重新走全流程；
     * 2. 收据在（含到上限的幂等步）→ 不再调用工具，用收据内容直接补一条 Done；
     * 3. 无法证明结果 → 落一条 StepResolution（awaiting_human）记下原因，抛异常挂起本 run：
     *    非幂等无收据，或幂等但连续 MAX_ATTEMPTS 次仍悬空（环境多半出了系统性问题）。
     *
     * 第 3 分支的 StepResolution 不能省：判定本身必须落盘——否则每次重启都重新发现
     * 这个悬空步、重新挂起，journal 却看不出它已进入人工流程，永远收敛不了。
     * 落了之后 replay() 会记住「已裁定」：收据没回来之前再重启，直接重抛异常、
     * 不再写第二条裁定——否则 M2 的事件订阅、M5 的审计会为每次重启各开一张工单。
     */
    private void adjudicate(RunId runId, List<Uncertain> uncertain, Set<Integer> adjudicated) {
        for (Uncertain u : uncertain) {
            if (u.idempotent() && u.attempt() < MAX_ATTEMPTS) {
                continue;
            }
            Receipt receipt = receipts.find(new IdempotencyKey(tenant, runId, u.toolUseId())).orElse(null);
            if (receipt != null) {
                journal.append(new JournalEvent.StepResolution(u.stepId(), u.toolUseId(),
                        "replayed_from_receipt", receipt.content()));
                journal.append(new JournalEvent.StepDone(u.stepId(), u.toolUseId(), receipt.content()));
                messages.add(Msg.tool(u.toolUseId(), receipt.content()));
                continue;
            }
            if (adjudicated.contains(u.stepId().seq())) {
                // 已裁定过等人、收据也还没回来：静默重抛同一条异常，不再堆第二条裁定
                throw new UncertainStepException(u, reasonOf(u));
            }
            String reason = reasonOf(u);
            journal.append(new JournalEvent.StepResolution(u.stepId(), u.toolUseId(), "awaiting_human", reason));
            throw new UncertainStepException(u, reason);
        }
    }

    /** 挂起理由：非幂等无收据说不清结果；幂等重跑到上限说明环境出了系统性问题。 */
    private String reasonOf(Uncertain u) {
        return u.idempotent()
                ? "重试 " + MAX_ATTEMPTS + " 次仍无结果"
                : "结果未知且无收据，不可自动重试";
    }

    /** 从 journal 重放同 runId 的既有进度：跑没跑过、跑到哪、轨迹长什么样、哪些步悬空。 */
    private Replay replay(RunId runId) {
        List<Msg> rebuilt = new ArrayList<>();
        Map<Integer, JournalEvent.StepPlanned> plannedBySeq = new HashMap<>();
        Map<Integer, Integer> maxAttempt = new HashMap<>();
        Set<Integer> resolved = new HashSet<>();
        Set<Integer> adjudicated = new HashSet<>();
        boolean started = false;
        boolean finished = false;
        String answer = null;
        String task = null;
        for (JournalEvent event : journal.scan()) {
            if (!event.runId().equals(runId)) {
                continue;
            }
            switch (event) {
                case JournalEvent.RunStarted ev -> {
                    started = true;
                    task = ev.task();
                }
                case JournalEvent.StepPlanned ev -> plannedBySeq.put(ev.stepId().seq(), ev);
                case JournalEvent.StepDispatched ev -> maxAttempt.merge(ev.stepId().seq(), ev.attempt(), Math::max);
                case JournalEvent.StepDone ev -> {
                    rebuilt.add(Msg.tool(ev.toolUseId(), ev.content()));
                    resolved.add(ev.stepId().seq());
                }
                case JournalEvent.StepFailed ev -> {
                    rebuilt.add(Msg.tool(ev.toolUseId(), ev.error()));
                    resolved.add(ev.stepId().seq());
                }
                case JournalEvent.RunFinished ev -> {
                    finished = true;
                    answer = ev.answer();
                }
                case JournalEvent.StepResolution ev -> adjudicated.add(ev.stepId().seq());
            }
        }
        if (started) {
            rebuilt.add(0, Msg.user(task));
        }
        return new Replay(started, finished, answer,
                resolved.stream().max(Integer::compareTo).orElse(-1),
                rebuilt, uncertainOf(runId, plannedBySeq, maxAttempt, resolved), adjudicated, maxAttempt);
    }

    /** 悬空步 = 已派发（Dispatched）但没有任何终态（Done/Failed）的步，配上计划段的元数据。 */
    private static List<Uncertain> uncertainOf(RunId runId,
            Map<Integer, JournalEvent.StepPlanned> plannedBySeq, Map<Integer, Integer> maxAttempt,
            Set<Integer> resolved) {
        List<Uncertain> uncertain = new ArrayList<>();
        for (int seq : maxAttempt.keySet()) {
            if (!resolved.contains(seq) && plannedBySeq.containsKey(seq)) {
                JournalEvent.StepPlanned planned = plannedBySeq.get(seq);
                uncertain.add(new Uncertain(new StepId(runId.value(), seq),
                        planned.toolUseId(), planned.toolName(), planned.idempotent(), maxAttempt.get(seq)));
            }
        }
        return uncertain;
    }
}
