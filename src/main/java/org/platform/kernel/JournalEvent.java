package org.platform.kernel;

import java.util.Objects;

import org.platform.kernel.id.RunId;
import org.platform.kernel.id.StepId;
import org.platform.kernel.id.TenantId;

/**
 * journal 事件：sealed 七种。RunStarted/RunWaiting/RunFinished 标注 run 级状态
 * （RUNNING → WAITING_APPROVAL →（批条落地续跑）RUNNING → … → RunFinished）；
 * StepPlanned/StepDispatched/StepDone|Failed 三段按 toolUseId 对齐——身份看 toolUseId，
 * stepId.seq 只记事件发生的位置，重放不得拿它当 join key（真 LLM 重放不保证序列一致，seq 会漂移）。
 * 每条事件自带 tenantId：runId 只能定位 run，定位不了「谁的 run」——没有 tenant 维度，
 * 猜中一个 runId 就能重建出别家的完整轨迹。事件身份因此是 (tenantId, runId)，
 * journal 主键随之变成 (tenantId, runId, seq)，scan 端口按它收敛。
 * 落盘格式由 FileJournal 决定，kernel 不依赖序列化器。
 */
public sealed interface JournalEvent
        permits JournalEvent.RunStarted, JournalEvent.StepPlanned, JournalEvent.StepDispatched,
                JournalEvent.StepDone, JournalEvent.StepFailed, JournalEvent.StepResolution,
                JournalEvent.RunWaiting, JournalEvent.RunFinished {

    /** 事件归属的租户——第一隔离维度，与 runId 一起构成事件的完整身份。 */
    TenantId tenantId();

    /** 事件归属的 run——重放按它过滤。 */
    RunId runId();

    /** StepDone.source 的取值：真执行 / 崩溃后从收据回放补的。 */
    String SOURCE_EXECUTED = "executed";
    String SOURCE_REPLAYED_FROM_RECEIPT = "replayed_from_receipt";

    /** StepResolution.resolution 的取值：崩溃悬空转人工裁决 / 审批闸前挂起等批条。 */
    String RESOLUTION_AWAITING_HUMAN = "awaiting_human";
    String RESOLUTION_AWAITING_APPROVAL = "awaiting_approval";

    record RunStarted(TenantId tenantId, RunId runId, String task) implements JournalEvent {

        public RunStarted {
            Objects.requireNonNull(tenantId, "tenantId");
        }
    }

    /** 计划段：带 toolName / inputDigest / idempotent，重放裁决的分支全在这。 */
    record StepPlanned(TenantId tenantId, StepId stepId, String toolUseId, String toolName, String inputDigest,
                       boolean idempotent) implements JournalEvent {

        public StepPlanned {
            Objects.requireNonNull(tenantId, "tenantId");
        }

        @Override
        public RunId runId() {
            return new RunId(stepId.runId());
        }
    }

    record StepDispatched(TenantId tenantId, StepId stepId, String toolUseId, int attempt) implements JournalEvent {

        public StepDispatched {
            Objects.requireNonNull(tenantId, "tenantId");
        }

        @Override
        public RunId runId() {
            return new RunId(stepId.runId());
        }
    }

    /** 完成段：一次调用有了确定结果。source 记结果从哪来——executed / replayed_from_receipt。 */
    record StepDone(TenantId tenantId, StepId stepId, String toolUseId, String content, String source)
            implements JournalEvent {

        public StepDone {
            Objects.requireNonNull(tenantId, "tenantId");
        }

        @Override
        public RunId runId() {
            return new RunId(stepId.runId());
        }
    }

    record StepFailed(TenantId tenantId, StepId stepId, String toolUseId, String error) implements JournalEvent {

        public StepFailed {
            Objects.requireNonNull(tenantId, "tenantId");
        }

        @Override
        public RunId runId() {
            return new RunId(stepId.runId());
        }
    }

    /**
     * 裁决段：对悬空步的判定结论——判定本身必须落盘，否则挂起状态重启后不可见、永远收敛不了。
     * awaiting_human：崩溃悬空说不清结果，人工裁决的是「执行了没有」，result 预留裁决后的回填；
     * awaiting_approval：审批闸前挡下，确定没执行，人工要做的只是批或不批——
     * 批条落进审批仓后同 runId 续跑，该步重新派发、重过网关审批闸。
     * 回放收据的判定已并入 StepDone.source = replayed_from_receipt。
     */
    record StepResolution(TenantId tenantId, StepId stepId, String toolUseId, String resolution, String reason,
                          String result) implements JournalEvent {

        public StepResolution {
            Objects.requireNonNull(tenantId, "tenantId");
        }

        @Override
        public RunId runId() {
            return new RunId(stepId.runId());
        }
    }

    /**
     * run 级状态事件：因等审批挂起（RUNNING → WAITING_APPROVAL）。续跑即回 RUNNING，
     * 再撞闸会再落一条——每次转移一条，订阅方（审计/工单）按它开窗，不必去数步级事件。
     * 步级悬而未决仍由 StepResolution(awaiting_approval) 表达，两者职责分开。
     */
    record RunWaiting(TenantId tenantId, RunId runId, String reason) implements JournalEvent {

        public RunWaiting {
            Objects.requireNonNull(tenantId, "tenantId");
        }
    }

    record RunFinished(TenantId tenantId, RunId runId, String answer) implements JournalEvent {

        public RunFinished {
            Objects.requireNonNull(tenantId, "tenantId");
        }
    }
}
