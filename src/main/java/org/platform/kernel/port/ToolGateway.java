package org.platform.kernel.port;

import org.platform.demo.ToolResult;
import org.platform.demo.ToolUse;
import org.platform.kernel.id.StepCtx;

/**
 * 工具网关：工具执行的统一入口，幂等性与重放裁决由实现侧负责。
 * 协议类型暂在 demo 包，后续上移时只需改这里的 import。
 */
public interface ToolGateway {

    /** 执行一次工具调用：ctx 定位本步，call.id 是幂等键。 */
    ToolResult invoke(StepCtx ctx, ToolUse call);

    /** 工具是否幂等：StepPlanned 落盘与重放裁决用。 */
    boolean idempotent(String toolName);
}
