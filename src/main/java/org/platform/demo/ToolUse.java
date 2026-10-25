package org.platform.demo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 模型发出的一次工具调用请求。id 全程透传、绝不能丢——M1 拿它做幂等键；input 以原始 JSON 字符串透传。 */
public record ToolUse(String id, String name, String input) {

    /** input 的 sha256 摘要：幂等比对「同输入」用。 */
    public String inputDigest() {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
