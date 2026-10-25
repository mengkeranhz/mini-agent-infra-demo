package org.platform.kernel;

/** 恢复撞上悬空步且无法自动续跑——挂起等人。reason 说明为什么（无收据 / 重试到上限）。 */
public final class UncertainStepException extends RuntimeException {

    private final Uncertain step;

    public UncertainStepException(Uncertain step, String reason) {
        super("悬空步 " + step.stepId().runId() + "#" + step.stepId().seq() + " " + step.toolUseId() + " ("
                + step.toolName() + " " + step.inputDigest().substring(0, 19) + "...)：" + reason + "，等待人工介入");
        this.step = step;
    }

    /** 卡住的那一步（含 inputDigest，人工裁决看「到底发了什么」）。 */
    public Uncertain step() {
        return step;
    }
}
