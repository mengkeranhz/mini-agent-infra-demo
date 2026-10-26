package org.platform.kernel.port;

/** 策略裁决三态：放行 / 拒绝 / 升级给人批。 */
public enum Decision {

    /** 放行，继续走后面的闸。 */
    ALLOW,

    /** 拒绝：本次调用不发生，错误结果回轨迹。 */
    DENY,

    /** 升级：要有人批（ApprovalRecord 七字段核对通过）才继续。 */
    ASK
}
