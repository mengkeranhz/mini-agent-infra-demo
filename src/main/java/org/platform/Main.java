package org.platform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

import org.platform.demo.AgentLoop;
import org.platform.demo.FileJournal;
import org.platform.demo.FileReceiptStore;
import org.platform.demo.MemoryApprovalStore;
import org.platform.demo.MemoryArtifactStore;
import org.platform.demo.MemoryAuditLog;
import org.platform.demo.StubModelGateway;
import org.platform.demo.tools.FakeMailbox;
import org.platform.demo.tools.GatedToolGateway;
import org.platform.demo.tools.SendMessageTool;
import org.platform.demo.tools.StaticPolicy;
import org.platform.demo.tools.ToolRegistry;
import org.platform.demo.tools.WebSearchTool;
import org.platform.kernel.JournalEvent;
import org.platform.kernel.id.RunId;
import org.platform.kernel.id.StepId;
import org.platform.kernel.id.TenantId;
import org.platform.kernel.port.ApprovalStore;
import org.platform.kernel.port.ArtifactStore;
import org.platform.kernel.port.AuditLog;
import org.platform.kernel.port.HarnessAdapter;
import org.platform.kernel.port.Journal;
import org.platform.kernel.port.ModelGateway;
import org.platform.kernel.port.Principal;
import org.platform.kernel.port.ReceiptStore;
import org.platform.kernel.port.Scope;
import org.platform.kernel.port.ToolGateway;

/** 基座装配：M2——十二步网关是唯一出口，权限、审批、幂等、审计四道闸全在这条路上。 */
public class Main {

    public static void main(String[] args) throws IOException {
        // M2 换真实租户来源；这里必须显式给值——不设全局默认租户，默认等于全平台共用一个分区
        TenantId tenant = new TenantId("demo");
        RunId runId = RunId.generate();
        System.out.println("runId = " + runId.value());
        ModelGateway model = new StubModelGateway();
        ReceiptStore receipts = new FileReceiptStore(Path.of("target", "receipts"));
        FakeMailbox mailbox = new FakeMailbox();
        ToolRegistry registry = new ToolRegistry()
                .register(new WebSearchTool(), "1.0.0", Set.of(tenant), "web", Set.of("query"), true)
                .register(new SendMessageTool(mailbox), "1.2.0", Set.of(tenant), "mailbox",
                        Set.of("to", "campaign", "seq", "msg"), true);
        Principal.Machine agent = new Principal.Machine("demo-agent", Set.of(Scope.INVOKE, Scope.READ));
        ApprovalStore approvals = new MemoryApprovalStore();
        ArtifactStore artifacts = new MemoryArtifactStore();
        MemoryAuditLog audit = new MemoryAuditLog();
        ToolGateway tools = new GatedToolGateway(registry, agent, StaticPolicy.allowAll(),
                approvals, receipts, artifacts, audit);
        Journal journal = new FileJournal(Path.of("target", "journal"));
        // 契约同 M1：receipts 必须与网关里的是同一个实例——恢复裁决查的收据就是网关存的收据
        HarnessAdapter harness = new AgentLoop(tenant, tools, model, journal, receipts);
        String answer = harness.run(runId, "跑通幂等 + 非幂等两个工具");
        System.out.println("answer = " + answer);
        System.out.println("---- journal scan ----");
        for (JournalEvent event : journal.scan(tenant, runId, -1L)) {
            System.out.println(describe(event));
        }
        System.out.println("---- audit ----");
        for (AuditLog.Event event : audit.events()) {
            System.out.println(event.outcome() + " " + event.toolName()
                    + (event.toolVersion() == null ? "" : "@" + event.toolVersion())
                    + " " + event.toolUseId() + " by " + event.principal());
        }
    }

    private static String describe(JournalEvent e) {
        return switch (e) {
            case JournalEvent.RunStarted ev -> ev.runId().value() + " RunStarted: " + ev.task();
            case JournalEvent.StepPlanned ev -> key(ev.stepId()) + " StepPlanned: " + ev.toolName()
                    + (ev.idempotent() ? " [幂等]" : " [非幂等]") + " " + ev.inputDigest() + " " + ev.toolUseId();
            case JournalEvent.StepDispatched ev -> key(ev.stepId()) + " StepDispatched: " + ev.toolUseId()
                    + " attempt=" + ev.attempt();
            case JournalEvent.StepDone ev -> key(ev.stepId()) + " StepDone: " + ev.toolUseId() + " -> " + ev.content()
                    + (!JournalEvent.SOURCE_EXECUTED.equals(ev.source()) ? " [" + ev.source() + "]" : "");
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
