package org.platform.kernel;

import java.util.List;
import java.util.function.Function;

/**
 * 恢复撞上悬空步且无法自动续跑——挂起等人。所有等人的步一次打包抛出：
 * 人工一趟看全、一趟处理完，而不是第一个悬空步来一趟、修完再撞下一个再来一趟。
 * reason 说明每个步为什么等人（无收据 / 重试到上限），由裁决方（harness）给定。
 */
public final class UncertainStepException extends RuntimeException {

    private final List<Uncertain> steps;

    public UncertainStepException(List<Uncertain> steps, Function<Uncertain, String> reasonOf) {
        super(describe(steps, reasonOf));
        this.steps = List.copyOf(steps);
    }

    /** 卡住的步，按 seq 升序（含 inputDigest，人工裁决看「到底发了什么」）。 */
    public List<Uncertain> steps() {
        return steps;
    }

    private static String describe(List<Uncertain> steps, Function<Uncertain, String> reasonOf) {
        StringBuilder msg = new StringBuilder();
        for (Uncertain step : steps) {
            if (msg.length() > 0) {
                msg.append('；');
            }
            msg.append("悬空步 ").append(step.runId().value()).append('#').append(step.stepId().seq())
                    .append(' ').append(step.toolUseId())
                    .append(" (").append(step.toolName()).append(' ')
                    .append(step.inputDigest(), 0, Math.min(19, step.inputDigest().length())).append("...)")
                    .append('：').append(reasonOf.apply(step));
        }
        msg.append("，等待人工介入");
        if (steps.size() > 1) {
            msg.append("（共 ").append(steps.size()).append(" 步，一次打包处理）");
        }
        return msg.toString();
    }
}
