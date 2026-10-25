package org.platform.demo.tools;

import java.util.Optional;

import org.platform.demo.ToolResult;
import org.platform.demo.ToolUse;
import org.platform.kernel.ExternalIdempotencyKey;

/**
 * demo 工具的统一抽象。
 * idempotent() 决定崩溃恢复的重放策略：幂等工具可安全重跑，非幂等工具只能靠 journal 续跑、绝不重放；
 * externalKey() 是交给下游的去重凭证——有下游且下游认键的工具才提供，默认无；
 * 非幂等工具不提供会在注册期被拒（ToolRouter 把关）：没有执行点的约定，等于没有约定。
 */
public interface Tool {

    String name();

    boolean idempotent();

    /** 注册期声明：本工具会给下游提供外层幂等键（externalKey）。默认 false。 */
    default boolean providesExternalKey() {
        return false;
    }

    ToolResult invoke(ToolUse use);

    /** 外层幂等凭证：下游按它认出「这单见过」。空 = 无下游或不支持去重。 */
    default Optional<ExternalIdempotencyKey> externalKey(ToolUse use) {
        return Optional.empty();
    }
}
