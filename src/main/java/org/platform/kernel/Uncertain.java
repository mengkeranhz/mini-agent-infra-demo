package org.platform.kernel;

import org.platform.kernel.id.StepId;

/**
 * 悬空步：上次进程死在「已声明发起（Dispatched 已落盘）、结果（Done/Failed）未落盘」之间的调用。
 * journal 只能证明发起过，证明不了执行没执行、完成没完成——所以叫悬空。
 * attempt 是这个步已发起过的次数（journal 里同一 seq 最大 attempt 号），重试上限的计数依据。
 * 恢复裁决以它为单位，人工介入时看到的也是它。
 */
public record Uncertain(StepId stepId, String toolUseId, String toolName, boolean idempotent, int attempt) {
}
