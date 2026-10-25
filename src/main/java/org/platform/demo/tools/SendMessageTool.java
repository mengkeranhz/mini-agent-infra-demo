package org.platform.demo.tools;

import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.platform.demo.ToolResult;
import org.platform.demo.ToolUse;
import org.platform.kernel.ExternalIdempotencyKey;

/**
 * 非幂等工具替身：每次调用都真实发送一次，崩溃后绝不能重放。
 * 外层幂等键的业务键由调用方在参数里给出：to/campaign/seq——工具只负责拼装，不做内容摘要。
 */
public final class SendMessageTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FakeMailbox mailbox;

    public SendMessageTool(FakeMailbox mailbox) {
        this.mailbox = mailbox;
    }

    @Override
    public String name() {
        return "send_message";
    }

    @Override
    public boolean idempotent() {
        return false;
    }

    @Override
    public boolean providesExternalKey() {
        return true;
    }

    @Override
    public Optional<ExternalIdempotencyKey> externalKey(ToolUse use) {
        try {
            String to = MAPPER.readTree(use.input()).path("to").asText(null);
            String campaign = MAPPER.readTree(use.input()).path("campaign").asText(null);
            long seq = MAPPER.readTree(use.input()).path("seq").asLong(-1);
            if (to == null || campaign == null || seq < 0) {
                return Optional.empty();   // 拼不出业务键就没有可去重的身份
            }
            return Optional.of(new ExternalIdempotencyKey("stdout", "print", to + "/" + campaign + "/" + seq));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    @Override
    public ToolResult invoke(ToolUse use) {
        Optional<ExternalIdempotencyKey> key = externalKey(use);
        if (key.isEmpty()) {
            return new ToolResult(use.id(), "bad args: 业务键需要 to/campaign/seq", true);
        }
        String msg;
        try {
            msg = MAPPER.readTree(use.input()).path("msg").asText();
        } catch (JsonProcessingException e) {
            return new ToolResult(use.id(), "bad args: " + use.input(), true);
        }
        return new ToolResult(use.id(), mailbox.deliver(key.orElseThrow(), msg), false);
    }
}
