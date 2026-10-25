package org.platform;

import java.io.IOException;
import java.nio.file.Path;

import org.platform.demo.AgentLoop;
import org.platform.demo.FileJournal;
import org.platform.demo.FileReceiptStore;
import org.platform.demo.IdempotentToolWrapper;
import org.platform.demo.StubModelGateway;
import org.platform.demo.tools.SendMessageTool;
import org.platform.demo.tools.ToolRouter;
import org.platform.demo.tools.WebSearchTool;
import org.platform.kernel.JournalEvent;
import org.platform.kernel.id.RunId;
import org.platform.kernel.id.StepId;
import org.platform.kernel.id.TenantId;
import org.platform.kernel.port.HarnessAdapter;
import org.platform.kernel.port.Journal;
import org.platform.kernel.port.ModelGateway;
import org.platform.kernel.port.ReceiptStore;
import org.platform.kernel.port.ToolGateway;

/** 基座装配：网关与 journal 显式拼好，经构造器注入 harness；基座只见 HarnessAdapter 契约。 */
public class Main {

    public static void main(String[] args) throws IOException {
        TenantId tenant = TenantId.DEFAULT;
        RunId runId = RunId.generate();
        System.out.println("runId = " + runId.value());
        ModelGateway model = new StubModelGateway();
        ReceiptStore receipts = new FileReceiptStore(Path.of("target", "receipts.jsonl"));
        ToolGateway tools = new IdempotentToolWrapper(new ToolRouter(new WebSearchTool(), new SendMessageTool()), receipts);
        Journal journal = new FileJournal(Path.of("target", "journal.jsonl"));
        HarnessAdapter harness = new AgentLoop(tenant, tools, model, journal, receipts);
        String answer = harness.run(runId, "跑通幂等 + 非幂等两个工具");
        System.out.println("answer = " + answer);
        System.out.println("---- journal scan ----");
        for (JournalEvent event : journal.scan()) {
            System.out.println(describe(event));
        }
    }

    private static String describe(JournalEvent e) {
        return switch (e) {
            case JournalEvent.RunStarted ev -> ev.runId().value() + " RunStarted: " + ev.task();
            case JournalEvent.StepPlanned ev -> key(ev.stepId()) + " StepPlanned: " + ev.toolName()
                    + (ev.idempotent() ? " [幂等]" : " [非幂等]") + " " + ev.inputDigest() + " " + ev.toolUseId();
            case JournalEvent.StepDispatched ev -> key(ev.stepId()) + " StepDispatched: " + ev.toolUseId()
                    + " attempt=" + ev.attempt();
            case JournalEvent.StepDone ev -> key(ev.stepId()) + " StepDone: " + ev.toolUseId() + " -> " + ev.content();
            case JournalEvent.StepFailed ev -> key(ev.stepId()) + " StepFailed: " + ev.toolUseId() + " ! " + ev.error();
            case JournalEvent.StepResolution ev -> key(ev.stepId()) + " StepResolution: " + ev.toolUseId()
                    + " " + ev.resolution() + " reason=" + ev.reason()
                    + (ev.result() != null ? " result=" + ev.result() : "");
            case JournalEvent.RunFinished ev -> ev.runId().value() + " RunFinished: " + ev.answer();
        };
    }

    private static String key(StepId stepId) {
        return stepId.runId() + "#" + stepId.seq();
    }
}
