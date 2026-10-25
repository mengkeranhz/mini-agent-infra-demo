package org.platform.demo;

/** 一次 ToolUse 的执行结果。toolUseId 必须原样回填 ToolUse.id，幂等键不能断。 */
public record ToolResult(String toolUseId, String content, boolean isError) {
}