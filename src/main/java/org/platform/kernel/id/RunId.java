package org.platform.kernel.id;

import java.util.Objects;
import java.util.UUID;

/** 一次 run 的唯一标识，journal 落盘时与 StepId 一起定位一条记录。 */
public record RunId(String value) {

    /**
     * 任何来源的构造（generate、journal 回放读回）都过同一道校验：
     * 非 null、可打印 ASCII（0x21..0x7E，天然排除空白与控制符）、长度 1..64、不含路径分隔符。
     * 脏 value 在类型边界处就炸，绝不带进 journal。
     */
    public RunId {
        requireValid(value, "RunId");
    }

    /** 同一套主键成分校验，供 StepId/TenantId/IdempotencyKey/Principal/ApprovalRecord 复用，主键不双标。 */
    public static void requireValid(String value, String kind) {
        Objects.requireNonNull(value, kind);
        // 额外禁 '/'、'\\' 与裸 "."/".."：id 会变成文件路径成分（journal/收据的目录名、文件名），放行就是路径穿越
        if (value.isEmpty() || value.length() > 64
                || value.equals(".") || value.equals("..")
                || !value.chars().allMatch(c -> c >= 0x21 && c <= 0x7E && c != '/' && c != '\\')) {
            throw new IllegalArgumentException("非法 " + kind + ": " + value);
        }
    }

    public static RunId generate() {
        return new RunId(UUID.randomUUID().toString());
    }
}
