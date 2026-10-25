package org.platform.kernel.id;

/** run 内单调递增的步号，一次工具调用 = 一个 step（Planned/Dispatched/Done 三段共用）；自带 runId，跨 run 各自计数。 */
public record StepId(String runId, int seq) {

    public StepId {
        RunId.requireValid(runId, "RunId");
        if (seq < 0) {
            throw new IllegalArgumentException("StepId.seq 不能为负: " + seq);
        }
    }

    /** 某 run 的第一步。 */
    public static StepId first(RunId runId) {
        return new StepId(runId.value(), 0);
    }

    public StepId next() {
        return new StepId(runId, seq + 1);
    }
}
