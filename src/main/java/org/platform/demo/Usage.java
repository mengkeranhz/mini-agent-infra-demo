package org.platform.demo;

/** 一次采样的 token 用量；字段先立着，M4 再接真实计费与预算控制。 */
public record Usage(long inputTokens, long outputTokens) {

    /** 桩与未知用量的默认值。 */
    public static final Usage ZERO = new Usage(0, 0);
}
