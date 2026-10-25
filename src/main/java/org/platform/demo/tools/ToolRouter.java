package org.platform.demo.tools;

import java.util.HashMap;
import java.util.Map;

import org.platform.demo.ToolResult;
import org.platform.demo.ToolUse;
import org.platform.kernel.id.StepCtx;
import org.platform.kernel.port.ToolGateway;

/** 工具网关实现：按名字路由到注入的工具表，未知工具只报错、不炸循环。 */
public final class ToolRouter implements ToolGateway {

    private final Map<String, Tool> tools = new HashMap<>();

    public ToolRouter(Tool... tools) {
        for (Tool tool : tools) {
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
