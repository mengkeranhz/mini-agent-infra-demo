package org.platform.demo.tools;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.platform.demo.ToolResult;
import org.platform.demo.ToolUse;
import org.platform.kernel.ExternalIdempotencyKey;

/** 非幂等工具替身：每次调用都真实发送一次，崩溃后绝不能重放。发送时带外层幂等键，下游 FakeMailbox 自己去重。 */
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
    public Optional<ExternalIdempotencyKey> externalKey(ToolUse use) {
        try {
            String msg = MAPPER.readTree(use.input()).path("msg").asText();
            return Optional.of(new ExternalIdempotencyKey("stdout", "print", sha256(msg)));
        } catch (JsonProcessingException e) {
            return Optional.empty();   // 参数都读不出来，没有可去重的业务键
        }
    }

    @Override
    public ToolResult invoke(ToolUse use) {
        Optional<ExternalIdempotencyKey> key = externalKey(use);
        JsonNode args;
        try {
            args = MAPPER.readTree(use.input());
        } catch (JsonProcessingException e) {
            return new ToolResult(use.id(), "bad args: " + use.input(), true);
        }
        String status = key.isPresent()
                ? mailbox.deliver(key.orElseThrow(), args.path("msg").asText())
                : "bad args, no key";
        return new ToolResult(use.id(), status, false);
    }

    private static String sha256(String s) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
