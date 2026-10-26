package org.platform.kernel;

import java.util.Objects;

import org.platform.kernel.id.RunId;
import org.platform.kernel.id.StepId;
import org.platform.kernel.id.TenantId;

/**
 * journal 事件：sealed 六种。RunStarted/RunFinished 框住一次 run；
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
                JournalEvent.RunFinished {

    /** 事件归属的租户——第一隔离维度，与 runId 一起构成事件的完整身份。 */
    TenantId tenantId();

    /** 事件归属的 run——重放按它过滤。 */
    RunId runId();

    /** StepDone.source 的取值：真执行 / 崩溃后从收据回放补的。 */
    String SOURCE_EXECUTED = "executed";
    String SOURCE_REPLAYED_FROM_RECEIPT = "replayed_from_receipt";

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
     * 裁决段：恢复时对悬空步的判定结论——判定本身必须落盘，否则挂起状态重启后不可见、永远收敛不了。
     * 只用于 awaiting_human：回放收据的判定已并入 StepDone.source = replayed_from_receipt；
     * reason 记为什么挂起，result 预留人工裁决后的回填。
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

    record RunFinished(TenantId tenantId, RunId runId, String answer) implements JournalEvent {

        public RunFinished {
            Objects.requireNonNull(tenantId, "tenantId");
        }
    }
}
