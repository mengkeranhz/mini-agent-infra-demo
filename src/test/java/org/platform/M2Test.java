package org.platform;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.platform.demo.AgentLoop;
import org.platform.demo.FileJournal;
import org.platform.demo.FileReceiptStore;
import org.platform.demo.MemoryApprovalStore;
import org.platform.demo.MemoryArtifactStore;
import org.platform.demo.MemoryAuditLog;
import org.platform.demo.MemoryReceiptStore;
import org.platform.demo.StubModelGateway;
import org.platform.demo.ToolResult;
import org.platform.demo.ToolUse;
import org.platform.demo.tools.FakeMailbox;
import org.platform.demo.tools.GatedToolGateway;
import org.platform.demo.tools.SendMessageTool;
import org.platform.demo.tools.StaticPolicy;
import org.platform.demo.tools.Tool;
import org.platform.demo.tools.ToolRegistry;
import org.platform.demo.tools.WebSearchTool;
import org.platform.kernel.ApprovalRecord;
import org.platform.kernel.JournalEvent;
import org.platform.kernel.id.RunId;
import org.platform.kernel.id.StepCtx;
import org.platform.kernel.id.StepId;
import org.platform.kernel.id.TenantId;
import org.platform.kernel.port.AuditLog;
import org.platform.kernel.port.Principal;
import org.platform.kernel.port.ReceiptStore;
import org.platform.kernel.port.Scope;
import org.platform.kernel.port.ToolGateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** M2 验收：十二步网关——权限、审批、幂等、审计四道闸全在唯一出口上，红线（Agent 不直连外部）不破。 */
class M2Test {

    private static final TenantId ACME = new TenantId("acme");
    private static final Principal.Machine AGENT = new Principal.Machine("agent-1", Set.of(Scope.INVOKE, Scope.READ));
    private static final Principal.Human APPROVER = new Principal.Human("admin-1", Set.of("approver"));

    /** 标准注册表：搜索（幂等）+ 发消息（非幂等、外层键），版本钉死、只给 acme。 */
    private static ToolRegistry registry(TenantId tenant, FakeMailbox mailbox) {
        return new ToolRegistry()
                .register(new WebSearchTool(), "1.0.0", Set.of(tenant), "web", Set.of("query"), true)
                .register(new SendMessageTool(mailbox), "1.2.0", Set.of(tenant), "mailbox",
                        Set.of("to", "campaign", "seq", "msg"), true);
    }

    /** 直接调网关用的最小上下文。 */
    private static StepCtx ctx(TenantId tenant, RunId run) {
        return new StepCtx(tenant, run, StepId.first(run));
    }

    private static ToolUse send(String toolUseId) {
        return new ToolUse(toolUseId, "send_message",
                "{\"to\":\"ops\",\"campaign\":\"m2\",\"seq\":1,\"msg\":\"hello m2\"}");
    }

    /** 按给定批条预置审批仓，装一个 send_message 走 ASK 的网关；批条为 null = 一张批条都没有。 */
    private static GatedToolGateway askGateway(ApprovalRecord approval, FakeMailbox mailbox, RunId run) {
        MemoryApprovalStore approvals = new MemoryApprovalStore();
        if (approval != null) {
            approvals.trySaveIfAbsent(approval);
        }
        return new GatedToolGateway(registry(ACME, mailbox), AGENT, StaticPolicy.asking("send_message"),
                approvals, new MemoryReceiptStore(), new MemoryArtifactStore(), new MemoryAuditLog());
    }

    @Test
    void 放行路径_接进harness_M1行为不变() throws IOException {
        Path journalDir = dataDir("m2-allow-journal");
        Path receiptsDir = dataDir("m2-allow-receipts");
        RunId runId = RunId.generate();
        FakeMailbox mailbox = new FakeMailbox();
        ReceiptStore receipts = new FileReceiptStore(receiptsDir);
        MemoryAuditLog audit = new MemoryAuditLog();
        ToolGateway tools = new GatedToolGateway(registry(ACME, mailbox), AGENT, StaticPolicy.allowAll(),
                new MemoryApprovalStore(), receipts, new MemoryArtifactStore(), audit);

        // 端口没动：网关换血，harness 一行不改——十二步全在 invoke 里
        String answer = new AgentLoop(ACME, tools, new StubModelGateway(),
                new FileJournal(journalDir), receipts).run(runId, "跑通幂等 + 非幂等两个工具");

        assertThat(answer).isEqualTo("消息已发出，run 结束。");
        assertThat(mailbox.deliveredCount()).isEqualTo(1);
        assertThat(new FileJournal(journalDir).scan(ACME, runId, -1L).stream()
                .filter(JournalEvent.StepDone.class::isInstance)).hasSize(2);
        // 审计：两次放行都留痕，七字段齐全，principal 是机器凭证
        assertThat(audit.events()).extracting(AuditLog.Event::outcome).containsExactly("executed", "executed");
        assertThat(audit.events()).allMatch(e -> e.principal().equals(AGENT) && e.toolVersion() != null);
    }

    @Test
    void 策略DENY_执行前拦截_零副作用_审计留痕() throws IOException {
        Path journalDir = dataDir("m2-deny-journal");
        RunId runId = RunId.generate();
        FakeMailbox mailbox = new FakeMailbox();
        MemoryAuditLog audit = new MemoryAuditLog();
        ReceiptStore receipts = new MemoryReceiptStore();
        ToolGateway tools = new GatedToolGateway(registry(ACME, mailbox), AGENT,
                StaticPolicy.denying("send_message"), new MemoryApprovalStore(), receipts,
                new MemoryArtifactStore(), audit);

        // 拒绝走错误结果不炸循环：桩模型见到 t-2 的工具消息就终答，run 照常收尾
        String answer = new AgentLoop(ACME, tools, new StubModelGateway(),
                new FileJournal(journalDir), receipts).run(runId, "任务");

        assertThat(answer).isEqualTo("消息已发出，run 结束。");   // 模型以为发了——审计知道没发
        assertThat(mailbox.deliveredCount()).isZero();
        assertThat(new FileJournal(journalDir).scan(ACME, runId, -1L).stream()
                .filter(JournalEvent.StepFailed.class::isInstance)
                .map(JournalEvent.StepFailed.class::cast)
                .filter(e -> "t-2".equals(e.toolUseId()))
                .findFirst().orElseThrow().error()).contains("策略拒绝");
        assertThat(audit.events()).extracting(AuditLog.Event::outcome)
                .containsExactly("executed", "policy_denied");
    }

    @Test
    void ASK无批条_不放行_工单带七字段() {
        FakeMailbox mailbox = new FakeMailbox();
        MemoryAuditLog audit = new MemoryAuditLog();
        RunId runId = RunId.generate();
        GatedToolGateway tools = new GatedToolGateway(registry(ACME, mailbox), AGENT,
                StaticPolicy.asking("send_message"), new MemoryApprovalStore(), new MemoryReceiptStore(),
                new MemoryArtifactStore(), audit);
        ToolUse call = send("t-9");

        ToolResult result = tools.invoke(ctx(ACME, runId), call);

        assertThat(result.isError()).isTrue();
        // 工单即拒绝消息：人照着它就能把批条七字段填全
        assertThat(result.content()).contains("需要人工审批").contains("无批条")
                .contains("send_message@1.2.0").contains(call.inputDigest())
                .contains("target=mailbox").contains("toolUseId=t-9");
        assertThat(mailbox.deliveredCount()).isZero();
        assertThat(audit.events()).extracting(AuditLog.Event::outcome).containsExactly("awaiting_approval");
    }

    @Test
    void 批条七字段一处不符即作废_全对才放行() {
        FakeMailbox mailbox = new FakeMailbox();
        RunId runId = RunId.generate();
        ToolUse call = send("t-9");
        Instant validUntil = Instant.now().plusSeconds(3600);

        // 摘要不符：批的是别的内容
        ApprovalRecord wrongDigest = new ApprovalRecord("send_message", "1.2.0",
                "sha256:" + "0".repeat(64), "mailbox", ACME, runId, "t-9", validUntil, APPROVER);
        assertThat(askGateway(wrongDigest, mailbox, runId).invoke(ctx(ACME, runId), call).content())
                .contains("摘要不符");
        // 版本不符：批的不是这个 release
        ApprovalRecord wrongVersion = new ApprovalRecord("send_message", "9.9.9",
                call.inputDigest(), "mailbox", ACME, runId, "t-9", validUntil, APPROVER);
        assertThat(askGateway(wrongVersion, mailbox, runId).invoke(ctx(ACME, runId), call).content())
                .contains("版本不符");
        // 目标资源不符
        ApprovalRecord wrongTarget = new ApprovalRecord("send_message", "1.2.0",
                call.inputDigest(), "smtp", ACME, runId, "t-9", validUntil, APPROVER);
        assertThat(askGateway(wrongTarget, mailbox, runId).invoke(ctx(ACME, runId), call).content())
                .contains("目标资源不符");
        // 过期：陈年批条等于没有批条
        ApprovalRecord expired = new ApprovalRecord("send_message", "1.2.0",
                call.inputDigest(), "mailbox", ACME, runId, "t-9", Instant.now().minusSeconds(1), APPROVER);
        assertThat(askGateway(expired, mailbox, runId).invoke(ctx(ACME, runId), call).content())
                .contains("已过期");
        // 七字段全对且未过期：放行，真发一次
        ApprovalRecord valid = new ApprovalRecord("send_message", "1.2.0",
                call.inputDigest(), "mailbox", ACME, runId, "t-9", validUntil, APPROVER);
        ToolResult approved = askGateway(valid, mailbox, runId).invoke(ctx(ACME, runId), call);
        assertThat(approved.isError()).isFalse();
        assertThat(approved.content()).isEqualTo("ok");
        assertThat(mailbox.deliveredCount()).isEqualTo(1);
    }

    @Test
    void 机器没有INVOKE_发起即拒() {
        FakeMailbox mailbox = new FakeMailbox();
        GatedToolGateway tools = new GatedToolGateway(registry(ACME, mailbox),
                new Principal.Machine("reader-only", Set.of(Scope.READ)), StaticPolicy.allowAll(),
                new MemoryApprovalStore(), new MemoryReceiptStore(), new MemoryArtifactStore(),
                new MemoryAuditLog());

        ToolResult result = tools.invoke(ctx(ACME, RunId.generate()), send("t-9"));

        // scope 检查在策略实现里（StaticPolicy）：没 INVOKE 连发起都不行，一步都走不到执行
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("策略拒绝");
        assertThat(mailbox.deliveredCount()).isZero();
    }

    @Test
    void 跨租户_注册表视图_别家工具不存在() {
        FakeMailbox mailbox = new FakeMailbox();
        TenantId eve = new TenantId("eve");
        RunId runId = RunId.generate();
        ToolRegistry acmeRegistry = registry(ACME, mailbox);
        MemoryAuditLog audit = new MemoryAuditLog();
        GatedToolGateway tools = new GatedToolGateway(acmeRegistry, AGENT, StaticPolicy.allowAll(),
                new MemoryApprovalStore(), new MemoryReceiptStore(), new MemoryArtifactStore(), audit);

        // eve 拿 acme 的工具名来调：查无此工具——与调一个不存在的名字同音，存在性都不泄露
        assertThat(tools.invoke(ctx(eve, runId), send("t-9")).content())
                .isEqualTo("unknown tool: send_message");
        assertThat(tools.invoke(ctx(ACME, runId), new ToolUse("t-x", "no_such", "{}")).content())
                .isEqualTo("unknown tool: no_such");
        // 视图同理：eve 看不见 acme 的任何工具
        assertThat(acmeRegistry.forTenant(eve).find("send_message")).isEmpty();
        assertThat(acmeRegistry.forTenant(eve).find("web_search")).isEmpty();
        assertThat(acmeRegistry.forTenant(ACME).find("send_message")).isPresent();
        assertThat(mailbox.deliveredCount()).isZero();
        assertThat(audit.events()).extracting(AuditLog.Event::outcome)
                .containsExactly("unknown_tool", "unknown_tool");
    }

    @Test
    void 未发布版本_调不动() {
        FakeMailbox mailbox = new FakeMailbox();
        ToolRegistry registry = new ToolRegistry()
                .register(new SendMessageTool(mailbox), "2.0.0-rc1", Set.of(ACME), "mailbox",
                        Set.of("to", "campaign", "seq", "msg"), false);   // 灰度版：注册了但不发布
        MemoryAuditLog audit = new MemoryAuditLog();
        GatedToolGateway tools = new GatedToolGateway(registry, AGENT, StaticPolicy.allowAll(),
                new MemoryApprovalStore(), new MemoryReceiptStore(), new MemoryArtifactStore(), audit);

        ToolResult result = tools.invoke(ctx(ACME, RunId.generate()), send("t-9"));

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("未发布").contains("2.0.0-rc1");
        assertThat(mailbox.deliveredCount()).isZero();
        assertThat(audit.events()).extracting(AuditLog.Event::outcome).containsExactly("unreleased_version");
    }

    @Test
    void schema校验_缺字段与坏JSON_执行前被拒() {
        FakeMailbox mailbox = new FakeMailbox();
        GatedToolGateway tools = new GatedToolGateway(registry(ACME, mailbox), AGENT, StaticPolicy.allowAll(),
                new MemoryApprovalStore(), new MemoryReceiptStore(), new MemoryArtifactStore(),
                new MemoryAuditLog());
        StepCtx ctx = ctx(ACME, RunId.generate());

        // 缺 to：发不出去的东西，执行前就得拦
        ToolResult missingField = tools.invoke(ctx, new ToolUse("t-a", "send_message",
                "{\"campaign\":\"m2\",\"seq\":1,\"msg\":\"hi\"}"));
        assertThat(missingField.isError()).isTrue();
        assertThat(missingField.content()).contains("缺少必填字段: to");
        // 坏 JSON：schema 校验先于执行
        ToolResult badJson = tools.invoke(ctx, new ToolUse("t-b", "send_message", "not-json"));
        assertThat(badJson.content()).contains("不是合法 JSON");
        // 合法输入照常放行
        ToolResult ok = tools.invoke(ctx, send("t-c"));
        assertThat(ok.isError()).isFalse();
        assertThat(mailbox.deliveredCount()).isEqualTo(1);
    }

    @Test
    void 大结果_轨迹只留引用_全文入artifact仓_重放一致() {
        MemoryArtifactStore artifacts = new MemoryArtifactStore();
        MemoryAuditLog audit = new MemoryAuditLog();
        ToolRegistry registry = new ToolRegistry()
                .register(new BigTool(), "1.0.0", Set.of(ACME), "report", Set.of(), true);
        GatedToolGateway tools = new GatedToolGateway(registry, AGENT, StaticPolicy.allowAll(),
                new MemoryApprovalStore(), new MemoryReceiptStore(), artifacts, audit, 1024);
        RunId runId = RunId.generate();
        StepCtx ctx = ctx(ACME, runId);
        ToolUse call = new ToolUse("t-big", "big_report", "{}");

        ToolResult first = tools.invoke(ctx, call);

        // 轨迹里只留说明与引用，8KB 不进上下文
        assertThat(first.content()).contains("超出上限").contains("artifact");
        assertThat(first.content().length()).isLessThan(200);
        // 全文在仓里，按 (tenant, run) 名下的引用回查
        String ref = ACME.value() + "/" + runId.value() + "/t-big";
        assertThat(artifacts.load(ACME, runId, ref)).contains("R".repeat(8 * 1024));
        // 别家的引用查不到——artifact 仓同样租户隔离
        assertThat(artifacts.load(new TenantId("eve"), runId, ref)).isEmpty();
        // 重放与首次返回一致：收据存的是守卫后的最终内容
        ToolResult replay = tools.invoke(ctx, call);
        assertThat(replay.content()).isEqualTo(first.content());
        assertThat(audit.events()).extracting(AuditLog.Event::outcome)
                .containsExactly("executed", "replayed_from_receipt");
    }

    @Test
    void 闸内幂等_同键只执行一次_换键照常执行() {
        FakeMailbox mailbox = new FakeMailbox();
        GatedToolGateway tools = new GatedToolGateway(registry(ACME, mailbox), AGENT, StaticPolicy.allowAll(),
                new MemoryApprovalStore(), new MemoryReceiptStore(), new MemoryArtifactStore(),
                new MemoryAuditLog());
        StepCtx ctx = ctx(ACME, RunId.generate());

        ToolResult first = tools.invoke(ctx, send("t-9"));
        ToolResult retry = tools.invoke(ctx, send("t-9"));

        assertThat(first.content()).isEqualTo("ok");
        assertThat(retry.content()).isEqualTo(first.content());
        assertThat(retry.isError()).isEqualTo(first.isError());
        assertThat(mailbox.deliveredCount()).isEqualTo(1);   // 拦双发的是平台这层，不是下游
        // 换 toolUseId = 新调用，平台层照常执行——但业务键也得换（seq=2）：
        // 下游只认外层键 to/campaign/seq，seq 不变就被当重放吞掉（M1 的课，M2 照样成立）
        ToolResult second = tools.invoke(ctx, new ToolUse("t-10", "send_message",
                "{\"to\":\"ops\",\"campaign\":\"m2\",\"seq\":2,\"msg\":\"hello again\"}"));
        assertThat(second.content()).isEqualTo("ok");
        assertThat(mailbox.deliveredCount()).isEqualTo(2);
    }

    @Test
    void 注册期闸_非幂等无外层键拒绝_同名重复拒绝() {
        assertThatThrownBy(() -> new ToolRegistry()
                .register(new NoKeySendTool(), "1.0.0", Set.of(ACME), "mailbox", Set.of(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("拒绝注册");
        assertThatThrownBy(() -> new ToolRegistry()
                .register(new WebSearchTool(), "1.0.0", Set.of(ACME), "web", Set.of("query"), true)
                .register(new WebSearchTool(), "1.1.0", Set.of(ACME), "web", Set.of("query"), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("同名");
    }

    @Test
    void ASK集成_无批条_run收尾零副作用_工单进journal() throws IOException {
        Path journalDir = dataDir("m2-ask-journal");
        RunId runId = RunId.generate();
        FakeMailbox mailbox = new FakeMailbox();
        MemoryAuditLog audit = new MemoryAuditLog();
        ReceiptStore receipts = new MemoryReceiptStore();
        ToolGateway tools = new GatedToolGateway(registry(ACME, mailbox), AGENT,
                StaticPolicy.asking("send_message"), new MemoryApprovalStore(), receipts,
                new MemoryArtifactStore(), audit);

        String answer = new AgentLoop(ACME, tools, new StubModelGateway(),
                new FileJournal(journalDir), receipts).run(runId, "任务");

        // 拒绝走错误结果：run 照常收尾（桩模型见到 t-2 的工具消息就终答），一条消息都没发
        assertThat(answer).isEqualTo("消息已发出，run 结束。");
        assertThat(mailbox.deliveredCount()).isZero();
        // 工单随 StepFailed 落进 journal——人工从这里看到要批什么
        assertThat(new FileJournal(journalDir).scan(ACME, runId, -1L).stream()
                .filter(JournalEvent.StepFailed.class::isInstance)
                .map(JournalEvent.StepFailed.class::cast)
                .filter(e -> "t-2".equals(e.toolUseId()))
                .findFirst().orElseThrow().error())
                .contains("需要人工审批").contains("toolUseId=t-2");
        assertThat(audit.events()).extracting(AuditLog.Event::outcome)
                .containsExactly("executed", "awaiting_approval");
        // 已知口子：run 已 RunFinished，人工补批后同 runId 续跑只会拿旧答案——
        // 批后重跑要走新 run，或等后续里程碑的挂起语义（M1 的 UncertainStepException 是现成挂起机制）
    }

    /**
     * 固定落盘根目录：target/test-data/ 下，跑完留档供肉眼检查；mvn clean 顺带清掉。
     * 每次先删旧目录（journal/收据都按 tenant 分目录），断言才算得了数。
     */
    private static Path dataDir(String name) throws IOException {
        Path dir = Path.of("target", "test-data", name);
        if (Files.exists(dir)) {
            try (var entries = Files.walk(dir)) {
                entries.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
        }
        Files.createDirectories(dir);
        return dir;
    }

    /** 大结果替身：幂等、返回 8KB 文本——大小守卫与 artifact offload 的驱动。 */
    static final class BigTool implements Tool {

        @Override
        public String name() {
            return "big_report";
        }

        @Override
        public boolean idempotent() {
            return true;
        }

        @Override
        public ToolResult invoke(ToolUse use) {
            return new ToolResult(use.id(), "R".repeat(8 * 1024), false);
        }
    }

    /** 有副作用却不认外层键的反例工具——注册期就该被拒（M1Test 同款反例，闸移到了注册表）。 */
    static final class NoKeySendTool implements Tool {

        @Override
        public String name() {
            return "send_message";
        }

        @Override
        public boolean idempotent() {
            return false;
        }

        @Override
        public ToolResult invoke(ToolUse use) {
            return new ToolResult(use.id(), "不应被注册，更不应被调用", true);
        }
    }
}
