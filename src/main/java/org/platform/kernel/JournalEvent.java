package org.platform.kernel;

import org.platform.kernel.id.RunId;
import org.platform.kernel.id.StepId;

/**
 * journal 事件：sealed 六种。RunStarted/RunFinished 框住一次 run；
 * StepPlanned/StepDispatched/StepDone|Failed 三段共用一个 stepId。
 * 落盘格式由 FileJournal 决定，kernel 不依赖序列化器。
 */
public sealed interface JournalEvent
        permits JournalEvent.RunStarted, JournalEvent.StepPlanned, JournalEvent.StepDispatched,
                JournalEvent.StepDone, JournalEvent.StepFailed, JournalEvent.StepResolution,
                JournalEvent.RunFinished {

    /** 事件归属的 run——重放按它过滤。 */
    RunId runId();

    record RunStarted(RunId runId, String task) implements JournalEvent {
    }

    /** 计划段：带 toolName / inputDigest / idempotent，重放裁决的分支全在这。 */
    record StepPlanned(StepId stepId, String toolUseId, String toolName, String inputDigest, boolean idempotent)
            implements JournalEvent {

        @Override
        public RunId runId() {
            return new RunId(stepId.runId());
        }
    }

    record StepDispatched(StepId stepId, String toolUseId, int attempt) implements JournalEvent {

        @Override
        public RunId runId() {
            return new RunId(stepId.runId());
        }
    }

    record StepDone(StepId stepId, String toolUseId, String content) implements JournalEvent {

        @Override
        public RunId runId() {
            return new RunId(stepId.runId());
        }
    }

    record StepFailed(StepId stepId, String toolUseId, String error) implements JournalEvent {

        @Override
        public RunId runId() {
            return new RunId(stepId.runId());
        }
    }

    /** 裁决段：恢复时对悬空步的判定结论——判定本身必须落盘，否则挂起状态重启后不可见、永远收敛不了。 */
    record StepResolution(StepId stepId, String toolUseId, String resolution, String detail)
            implements JournalEvent {

        @Override
        public RunId runId() {
            return new RunId(stepId.runId());
        }
    }

    record RunFinished(RunId runId, String answer) implements JournalEvent {
    }
}
