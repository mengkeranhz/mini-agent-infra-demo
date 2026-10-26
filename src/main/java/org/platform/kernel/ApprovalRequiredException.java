package org.platform.kernel;

/**
 * 工具调用被审批闸挡下（策略 ASK 且无有效批条）：没执行、没失败，这步没有结果——
 * 网关因此不走结果通道，直接抛本异常挂起。
 * 与 UncertainStepException 的分工：那边是「可能已执行、结果说不清」，人工裁决的是结果；
 * 这边确定没执行，人要做的只是批或不批——批条落进 ApprovalStore 后同 runId 续跑，
 * 该步重新派发、重过网关审批闸，无需任何结果裁决。
 */
public final class ApprovalRequiredException extends RuntimeException {

    public ApprovalRequiredException(String workOrder) {
        super(workOrder);
    }
}
