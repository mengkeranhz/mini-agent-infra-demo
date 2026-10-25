package org.platform.demo.tools;

import java.util.Optional;

import org.platform.demo.ToolResult;
import org.platform.demo.ToolUse;
import org.platform.kernel.ExternalIdempotencyKey;

/**
 * demo 工具的统一抽象。
 * idempotent() 决定崩溃恢复的重放策略：幂等工具可安全重跑，非幂等工具只能靠 journal 续跑、绝不重放；
 * externalKey() 是交给下游的去重凭证——有下游且下游认键的工具才提供，默认无。
 */
public interface Tool {

    String name();

    boolean idempotent();

    ToolResult invoke(ToolUse use);

    /** 外层幂等凭证：下游按它认出「这单见过」。空 = 无下游或不支持去重。 */
    default Optional<ExternalIdempotencyKey> externalKey(ToolUse use) {
        return Optional.empty();
    }
}
