package org.platform.demo.tools;

import org.platform.demo.ToolResult;
import org.platform.demo.ToolUse;

/**
 * demo 工具的统一抽象。
 * idempotent() 决定崩溃恢复的重放策略：幂等工具可安全重跑，非幂等工具只能靠 journal 续跑、绝不重放。
 */
public interface Tool {

    String name();

    boolean idempotent();

    ToolResult invoke(ToolUse use);
}