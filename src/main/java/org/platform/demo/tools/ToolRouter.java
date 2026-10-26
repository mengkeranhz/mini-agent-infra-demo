package org.platform.demo.tools;

import java.util.HashMap;
import java.util.Map;

import org.platform.demo.ToolResult;
import org.platform.demo.ToolUse;
import org.platform.kernel.id.StepCtx;
import org.platform.kernel.port.ToolGateway;

/**
 * 工具网关实现：按名字路由到注入的工具表，未知工具只报错、不炸循环。
 * 兼任注册期把关：有副作用（非幂等）的工具必须声明提供外层幂等键，否则拒绝注册——
 * M2 起这道闸移入 ToolRegistry，注册走它；本类保留给 M1 验收装配，M2 装配见 GatedToolGateway。
 */
public final class ToolRouter implements ToolGateway {

    private final Map<String, Tool> tools = new HashMap<>();

    public ToolRouter(Tool... tools) {
        for (Tool tool : tools) {
            if (!tool.idempotent() && !tool.providesExternalKey()) {
                throw new IllegalArgumentException("拒绝注册 " + tool.name()
                        + "：非幂等工具必须提供外层幂等键——副作用工具不认键，下游裸奔");
            }
            this.tools.put(tool.name(), tool);
        }
    }

    @Override
    public ToolResult invoke(StepCtx ctx, ToolUse call) {
        Tool tool = tools.get(call.name());
        if (tool == null) {
            return new ToolResult(call.id(), "unknown tool: " + call.name(), true);
        }
        return tool.invoke(call);
    }

    @Override
    public boolean idempotent(String toolName) {
        Tool tool = tools.get(toolName);
        return tool != null && tool.idempotent();
    }
}
