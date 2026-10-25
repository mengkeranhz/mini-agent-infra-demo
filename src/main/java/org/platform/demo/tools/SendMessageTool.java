package org.platform.demo.tools;

import org.platform.demo.ToolResult;
import org.platform.demo.ToolUse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 非幂等工具替身：每次调用都真实发送一次，崩溃后绝不能重放。 */
public final class SendMessageTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "send_message";
    }

    @Override
    public boolean idempotent() {
        return false;
    }

    @Override
    public ToolResult invoke(ToolUse use) {
        JsonNode args;
        try {
            args = MAPPER.readTree(use.input());
        } catch (JsonProcessingException e) {
            return new ToolResult(use.id(), "bad args: " + use.input(), true);
        }
        System.out.println("SENT: " + args.path("msg").asText());
        return new ToolResult(use.id(), "ok", false);
    }
}