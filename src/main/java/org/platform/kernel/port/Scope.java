package org.platform.kernel.port;

/**
 * 机器的授权范围。刻意没有 APPROVE：审批是人独有的动作（ApprovalRecord.approver 的类型就是 Human），
 * scope 表里多这一项，模型自我审批就有了合法通道——四道闸里的审批闸当场作废。
 * INVOKE 的检查在策略实现里（demo 的 StaticPolicy）：网关只走十二步，不开第十三步的口子。
 */
public enum Scope {

    /** 发起工具调用。 */
    INVOKE,

    /** 读轨迹与结果。 */
    READ,

    /** 与 run 交互（补上下文、追问）。 */
    INTERACT,

    /** 取消 run。 */
    CANCEL
}
