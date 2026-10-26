package org.platform.kernel.port;

import org.platform.demo.ToolResult;
import org.platform.demo.ToolUse;
import org.platform.kernel.id.StepCtx;
import org.platform.kernel.id.TenantId;

/**
 * 工具网关：工具执行的统一入口，幂等性与重放裁决由实现侧负责。
 * 协议类型暂在 demo 包，后续上移时只需改这里的 import。
 */
public interface ToolGateway {

    /** 执行一次工具调用：ctx 定位本步，call.id 是幂等键。 */
    ToolResult invoke(StepCtx ctx, ToolUse call);

    /** 工具是否幂等：StepPlanned 落盘与重放裁决用。与 invoke 第 1 步同一口径——按租户视图查，
     *  本租户看不见的工具（别家的、不存在的）一律 false：查询类口子不得绕过租户检查。
     *  M3 多版本并存时在此追加 toolVersion 参数。 */
    boolean idempotent(TenantId tenant, String toolName);
}
