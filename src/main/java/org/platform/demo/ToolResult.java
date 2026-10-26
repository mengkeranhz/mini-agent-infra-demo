package org.platform.demo;

/**
 * 一次 ToolUse 的执行结果。toolUseId 必须原样回填 ToolUse.id，幂等键不能断。
 * 有结论才走这里：执行成功 / 工具明确报错；等审批没有结果——网关抛 ApprovalRequiredException，不经本类型。
 */
public record ToolResult(String toolUseId, String content, boolean isError) {
}