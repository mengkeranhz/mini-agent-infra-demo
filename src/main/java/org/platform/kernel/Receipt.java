package org.platform.kernel;

/** 一次工具执行的收据：结果内容 + 成败标记，够回放用。 */
public record Receipt(String content, boolean isError) {
}
