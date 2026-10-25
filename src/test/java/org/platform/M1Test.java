package org.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.platform.demo.AgentLoop;
import org.platform.demo.FileJournal;
import org.platform.demo.FileReceiptStore;
import org.platform.demo.IdempotentToolWrapper;
import org.platform.demo.MemoryReceiptStore;
import org.platform.demo.StubModelGateway;
import org.platform.demo.ToolResult;
import org.platform.demo.ToolUse;
import org.platform.demo.tools.SendMessageTool;
import org.platform.demo.tools.FakeMailbox;
import org.platform.demo.tools.Tool;
import org.platform.demo.tools.ToolRouter;
import org.platform.demo.tools.WebSearchTool;
import org.platform.kernel.ExternalIdempotencyKey;
import org.platform.kernel.JournalEvent;
import org.platform.kernel.Receipt;
import org.platform.kernel.id.IdempotencyKey;
import org.platform.kernel.id.RunId;
import org.platform.kernel.id.StepCtx;
import org.platform.kernel.id.StepId;
import org.platform.kernel.id.TenantId;
import org.platform.kernel.port.Journal;
import org.platform.kernel.port.ReceiptStore;
import org.platform.kernel.port.ToolGateway;
import org.platform.kernel.UncertainStepException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** M1 验收：崩溃恢复副作用不重复、半行截断、并发同键只执行一次。 */
class M1Test {

    private static final String TASK = "跑通幂等 + 非幂等两个工具";

    @Test
    void 崩溃后副作用不重复() throws IOException {
        Path journalFile = dataFile("crash.jsonl");
        Path receiptsFile = dataFile("crash-receipts.jsonl");
        TenantId tenant = TenantId.DEFAULT;
        RunId runId = RunId.generate();
        FakeMailbox mailbox = new FakeMailbox();
        CountingSend send = new CountingSend(mailbox);

        // 闸门放在 Dispatched 之后、真发一次再崩：副作用已发生，收据与 StepDone 均未落盘（最难窗口）
        ReceiptStore store1 = new FileReceiptStore(receiptsFile);
        ToolGateway crashing = new IdempotentToolWrapper(
                new ToolRouter(new WebSearchTool(), new CrashAfterSend(send)), store1);
        assertThatThrownBy(() -> new AgentLoop(tenant, crashing, new StubModelGateway(),
                new FileJournal(journalFile), store1).run(runId, TASK))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("模拟崩溃");
        assertThat(send.count.get()).isEqualTo(1);

        // 重启 resume 同一 runId：非幂等悬空步无收据 → 拒绝自动重试（此刻仍只有 1 次 SENT）
        ReceiptStore store2 = new FileReceiptStore(receiptsFile);
        ToolGateway resumed = new IdempotentToolWrapper(
                new ToolRouter(new WebSearchTool(), send), store2);
        assertThatThrownBy(() -> new AgentLoop(tenant, resumed, new StubModelGateway(),
                new FileJournal(journalFile), store2).run(runId, TASK))
                .isInstanceOf(UncertainStepException.class)
                .hasMessageContaining("人工介入");
        assertThat(send.count.get()).isEqualTo(1);

        // 人工处理可能挂几小时，期间再重启一次：异常照抛，但 awaiting_human 不堆第二条
        ReceiptStore store2b = new FileReceiptStore(receiptsFile);
        ToolGateway resumedAgain = new IdempotentToolWrapper(
                new ToolRouter(new WebSearchTool(), send), store2b);
        assertThatThrownBy(() -> new AgentLoop(tenant, resumedAgain, new StubModelGateway(),
                new FileJournal(journalFile), store2b).run(runId, TASK))
                .isInstanceOf(UncertainStepException.class);
        assertThat(new FileJournal(journalFile).scan().stream()
                .filter(JournalEvent.StepResolution.class::isInstance)
                .map(JournalEvent.StepResolution.class::cast)
                .filter(ev -> "awaiting_human".equals(ev.resolution()))
                .toList()).hasSize(1);

        // 人工核实消息确已发出，补收据 → 再 resume：命中回放，run 收尾，SENT 总次数 == 1
        ReceiptStore human = new FileReceiptStore(receiptsFile);
        human.trySaveIfAbsent(new IdempotencyKey(tenant, runId, "t-2"),
                new Receipt("ok(人工确认已发出)", false));

        ReceiptStore store3 = new FileReceiptStore(receiptsFile);
        ToolGateway finishing = new IdempotentToolWrapper(
                new ToolRouter(new WebSearchTool(), send), store3);
        String answer = new AgentLoop(tenant, finishing, new StubModelGateway(),
                new FileJournal(journalFile), store3).run(runId, TASK);

        assertThat(answer).isEqualTo("消息已发出，run 结束。");
        assertThat(send.count.get()).isEqualTo(1);
        assertThat(mailbox.deliveredCount()).isEqualTo(1);
        List<JournalEvent> events = new FileJournal(journalFile).scan();
        assertThat(events.stream().filter(JournalEvent.RunStarted.class::isInstance)).hasSize(1);
        assertThat(events.stream().filter(JournalEvent.RunFinished.class::isInstance)).hasSize(1);
    }

    @Test
    void 半行JSON不被当有效事件() throws IOException {
        Path file = dataFile("tail.jsonl");
        Journal journal = new FileJournal(file);
        for (int i = 0; i < 5; i++) {
            journal.append(new JournalEvent.StepPlanned(
                    new StepId("run", i), "t-" + i, "web_search", "sha256:x", true));
        }
        Files.write(file, """
                {"kind":"StepD""".getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        Journal reopened = new FileJournal(file);
        assertThat(reopened.scan()).hasSize(5);
        assertThat(reopened.append(new JournalEvent.StepPlanned(
                new StepId("run", 5), "t-5", "web_search", "sha256:x", true))).isEqualTo(5);
    }

    @Test
    void 执行后崩溃不双发_内存收据挂起_落盘收据自动收敛() throws IOException {
        TenantId tenant = TenantId.DEFAULT;

        // —— 对照组：收据只在内存，重启即失 → 悬空步无收据，只能挂起等人工 ——
        Path memJournal = dataFile("w4-mem.jsonl");
        RunId memRun = RunId.generate();
        FakeMailbox memMailbox = new FakeMailbox();
        CountingSend memSend = new CountingSend(memMailbox);
        ToolGateway memWrapper = new IdempotentToolWrapper(
                new ToolRouter(new WebSearchTool(), memSend), new MemoryReceiptStore());
        assertThatThrownBy(() -> new AgentLoop(tenant, new CrashAfterReceiptSaved(memWrapper),
                new StubModelGateway(), new FileJournal(memJournal), new MemoryReceiptStore()).run(memRun, TASK))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("模拟崩溃");
        assertThat(memSend.count.get()).isEqualTo(1);
        assertThatThrownBy(() -> new AgentLoop(tenant,
                new IdempotentToolWrapper(new ToolRouter(new WebSearchTool(), memSend), new MemoryReceiptStore()),
                new StubModelGateway(), new FileJournal(memJournal), new MemoryReceiptStore()).run(memRun, TASK))
                .isInstanceOf(UncertainStepException.class)
                .hasMessageContaining("人工介入");
        assertThat(memSend.count.get()).isEqualTo(1);

        // —— 实验组：收据落盘，重启照读 → 裁决直接回放补 Done，不再 invoke，自动收敛到 RunFinished ——
        Path fileJournal = dataFile("w4-file.jsonl");
        Path fileReceipts = dataFile("w4-file-receipts.jsonl");
        RunId fileRun = RunId.generate();
        FakeMailbox fileMailbox = new FakeMailbox();
        CountingSend fileSend = new CountingSend(fileMailbox);
        ReceiptStore store1 = new FileReceiptStore(fileReceipts);
        ToolGateway fileWrapper = new IdempotentToolWrapper(
                new ToolRouter(new WebSearchTool(), fileSend), store1);
        assertThatThrownBy(() -> new AgentLoop(tenant, new CrashAfterReceiptSaved(fileWrapper),
                new StubModelGateway(), new FileJournal(fileJournal), store1).run(fileRun, TASK))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("模拟崩溃");
        assertThat(fileSend.count.get()).isEqualTo(1);

        ReceiptStore store2 = new FileReceiptStore(fileReceipts);
        String answer = new AgentLoop(tenant,
                new IdempotentToolWrapper(new ToolRouter(new WebSearchTool(), fileSend), store2),
                new StubModelGateway(), new FileJournal(fileJournal), store2).run(fileRun, TASK);

        assertThat(answer).isEqualTo("消息已发出，run 结束。");
        assertThat(fileSend.count.get()).isEqualTo(1);
        assertThat(fileMailbox.deliveredCount()).isEqualTo(1);
        List<JournalEvent> events = new FileJournal(fileJournal).scan();
        assertThat(events.stream()
                .filter(JournalEvent.StepDone.class::isInstance)
                .map(JournalEvent.StepDone.class::cast)
                .filter(e -> "t-2".equals(e.toolUseId()))
                .toList()).hasSize(1);
        assertThat(events.stream()
                .filter(JournalEvent.StepDone.class::isInstance)
                .map(JournalEvent.StepDone.class::cast)
                .filter(e -> "t-2".equals(e.toolUseId()))
                .findFirst().orElseThrow().source())
                .isEqualTo("replayed_from_receipt");
        assertThat(events.stream()
                .filter(JournalEvent.StepResolution.class::isInstance)
                .map(JournalEvent.StepResolution.class::cast)
                .map(JournalEvent.StepResolution::resolution)
                .toList()).doesNotContain("replayed_from_receipt");
        assertThat(events.stream().filter(JournalEvent.StepDispatched.class::isInstance).toList()).hasSize(2);
        assertThat(events.stream().filter(JournalEvent.RunFinished.class::isInstance)).hasSize(1);
    }

    @Test
    void 并发同键只执行一次() throws Exception {
        FakeMailbox mailbox = new FakeMailbox();
        CountingSend send = new CountingSend(mailbox);
        ToolGateway tools = new IdempotentToolWrapper(new ToolRouter(send), new MemoryReceiptStore());
        TenantId tenant = TenantId.DEFAULT;
        RunId runId = RunId.generate();
        StepCtx ctx = new StepCtx(tenant, runId, StepId.first(runId));
        ToolUse call = new ToolUse("t-7", "send_message", """
                {"to":"qa","campaign":"race","seq":1,"msg":"race"}""");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ToolResult> a = pool.submit(() -> tools.invoke(ctx, call));
            Future<ToolResult> b = pool.submit(() -> tools.invoke(ctx, call));
            ToolResult ra = a.get();
            ToolResult rb = b.get();
            assertThat(send.count.get()).isEqualTo(1);
            assertThat(mailbox.deliveredCount()).isEqualTo(1);
            assertThat(rb.content()).isEqualTo(ra.content());
            assertThat(rb.isError()).isEqualTo(ra.isError());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 幂等重试有上限_三次仍悬空转人工() throws IOException {
        Path journalFile = dataFile("attempts.jsonl");
        TenantId tenant = TenantId.DEFAULT;
        RunId runId = RunId.generate();
        AlwaysCrashWeb web = new AlwaysCrashWeb();

        // 初始 + 两次 resume：各真跑一次、各崩一次，attempt 1/2/3 依次递增
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> runOnce(tenant, journalFile, web, runId))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("模拟崩溃");
        }
        assertThat(web.count.get()).isEqualTo(3);

        // 第三次 resume：attempt=3 到上限 → 落 awaiting_human 并挂起，不再执行
        assertThatThrownBy(() -> runOnce(tenant, journalFile, web, runId))
                .isInstanceOf(UncertainStepException.class)
                .hasMessageContaining("重试 3 次仍无结果");
        assertThat(web.count.get()).isEqualTo(3);

        // 第五次 run：静默重抛，journal 不再增长
        assertThatThrownBy(() -> runOnce(tenant, journalFile, web, runId))
                .isInstanceOf(UncertainStepException.class);

        List<JournalEvent> events = new FileJournal(journalFile).scan();
        List<JournalEvent.StepDispatched> dispatches = events.stream()
                .filter(JournalEvent.StepDispatched.class::isInstance)
                .map(JournalEvent.StepDispatched.class::cast)
                .toList();
        assertThat(dispatches).hasSize(3);
        assertThat(dispatches.stream().map(JournalEvent.StepDispatched::attempt).toList())
                .containsExactly(1, 2, 3);
        assertThat(events.stream()
                .filter(JournalEvent.StepResolution.class::isInstance)
                .map(JournalEvent.StepResolution.class::cast)
                .filter(ev -> "awaiting_human".equals(ev.resolution()))
                .toList()).hasSize(1);
    }

    @Test
    void 外层幂等键_下游自己去重_不依赖平台幂等层() {
        // 注册期把关：有副作用又不认外层键的工具，根本进不了工具表
        assertThatThrownBy(() -> new ToolRouter(new WebSearchTool(), new NoKeySendTool()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("拒绝注册");

        // 刻意不走 IdempotentToolWrapper：平台幂等层等于不存在，只考验下游自己认不认得出重复
        FakeMailbox mailbox = new FakeMailbox();
        ToolGateway router = new ToolRouter(new WebSearchTool(), new SendMessageTool(mailbox));
        TenantId tenant = TenantId.DEFAULT;
        RunId runId = RunId.generate();
        StepCtx ctx = new StepCtx(tenant, runId, StepId.first(runId));

        // 同一业务键（to/campaign/seq 全同）的重试：返回首次的真实结果，不二次投递
        ToolResult first = router.invoke(ctx, new ToolUse("t-9", "send_message", """
                {"to":"zhangsan","campaign":"daily","seq":1,"msg":"OK"}"""));
        ToolResult retry = router.invoke(ctx, new ToolUse("t-9", "send_message", """
                {"to":"zhangsan","campaign":"daily","seq":1,"msg":"OK"}"""));
        assertThat(retry.content()).isEqualTo(first.content());
        assertThat(retry.isError()).isEqualTo(first.isError());
        assertThat(mailbox.deliveredCount()).isEqualTo(1);

        // 内容一模一样、业务键不同（seq=2）：两次合法操作，都得投
        ToolResult second = router.invoke(ctx, new ToolUse("t-9", "send_message", """
                {"to":"zhangsan","campaign":"daily","seq":2,"msg":"OK"}"""));
        assertThat(second.content()).isEqualTo("ok");
        assertThat(mailbox.deliveredCount()).isEqualTo(2);

        // 键的形状：businessKey 由参数派生，不是内容摘要
        Optional<ExternalIdempotencyKey> key = new SendMessageTool(mailbox).externalKey(new ToolUse("t-9", "send_message", """
                {"to":"zhangsan","campaign":"daily","seq":1,"msg":"OK"}"""));
        assertThat(key).isPresent();
        assertThat(key.orElseThrow().system()).isEqualTo("stdout");
        assertThat(key.orElseThrow().operation()).isEqualTo("print");
        assertThat(key.orElseThrow().businessKey()).isEqualTo("zhangsan/daily/1");
    }

    private static String runOnce(TenantId tenant, Path journalFile, Tool web, RunId runId) throws IOException {
        return new AgentLoop(tenant,
                new IdempotentToolWrapper(new ToolRouter(web), new MemoryReceiptStore()),
                new StubModelGateway(), new FileJournal(journalFile), new MemoryReceiptStore())
                .run(runId, TASK);
    }

    /** 固定落盘位置：target/test-data/ 下，跑完留档供肉眼检查；mvn clean 顺带清掉。每次先删旧文件，断言才算得了数。 */
    private static Path dataFile(String name) throws IOException {
        Path file = Path.of("target", "test-data", name);
        Files.createDirectories(file.getParent());
        Files.deleteIfExists(file);
        return file;
    }

    /** 计数替身：包住真实 SendMessageTool，数副作用真实发生了几次。 */
    static final class CountingSend implements Tool {

        final AtomicInteger count = new AtomicInteger();
        private final SendMessageTool delegate;

        CountingSend(FakeMailbox mailbox) {
            this.delegate = new SendMessageTool(mailbox);
        }

        @Override
        public String name() {
            return "send_message";
        }

        @Override
        public boolean idempotent() {
            return false;
        }

        @Override
        public boolean providesExternalKey() {
            return true;
        }

        @Override
        public ToolResult invoke(ToolUse use) {
            count.incrementAndGet();
            return delegate.invoke(use);
        }
    }

    /** 崩溃闸门：真发一次，返回前崩——收据与 StepDone 都来不及留（副作用已发生）。 */
    static final class CrashAfterSend implements Tool {

        private final Tool delegate;
        private boolean armed = true;

        CrashAfterSend(Tool delegate) {
            this.delegate = delegate;
        }

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public boolean idempotent() {
            return delegate.idempotent();
        }

        @Override
        public boolean providesExternalKey() {
            return true;
        }

        @Override
        public ToolResult invoke(ToolUse use) {
            ToolResult result = delegate.invoke(use);
            if (armed) {
                armed = false;
                throw new RuntimeException("模拟崩溃: 已发出，收据与 StepDone 均未落盘");
            }
            return result;
        }
    }

    /** 有副作用却不认外层键的反例工具——注册期就该被拒。 */
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

    /** 每次必崩的幂等工具：真实执行（计数）后抛异常——模拟下游持续故障的环境问题。 */
    static final class AlwaysCrashWeb implements Tool {

        final AtomicInteger count = new AtomicInteger();

        @Override
        public String name() {
            return "web_search";
        }

        @Override
        public boolean idempotent() {
            return true;
        }

        @Override
        public ToolResult invoke(ToolUse use) {
            count.incrementAndGet();
            new WebSearchTool().invoke(use);
            throw new RuntimeException("模拟崩溃: 下游持续故障");
        }
    }

    /** W4 闸门（网关层、包在幂等层外）：执行完且收据已入仓，StepDone 未写就崩。 */
    static final class CrashAfterReceiptSaved implements ToolGateway {

        private final ToolGateway inner;
        private boolean armed = true;

        CrashAfterReceiptSaved(ToolGateway inner) {
            this.inner = inner;
        }

        @Override
        public ToolResult invoke(StepCtx ctx, ToolUse call) {
            ToolResult result = inner.invoke(ctx, call);
            if (armed && "send_message".equals(call.name())) {
                armed = false;
                throw new RuntimeException("模拟崩溃: 收据已落盘，StepDone 未写");
            }
            return result;
        }

        @Override
        public boolean idempotent(String toolName) {
            return inner.idempotent(toolName);
        }
    }
}
