package org.platform.demo;

/**
 * 轨迹中的一条消息；M1 起由 Jackson 序列化写进 journal。
 * toolUseId 仅 TOOL 角色有值，其余角色为 null。
 */
public record Msg(Role role, String content, String toolUseId) {

    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    public static Msg user(String content) {
        return new Msg(Role.USER, content, null);
    }

    public static Msg assistant(String content) {
        return new Msg(Role.ASSISTANT, content, null);
    }

    /** 工具结果消息：必须回填 toolUseId，模型靠它与 tool_use 配对，审计链也靠它续上。 */
    public static Msg tool(String toolUseId, String content) {
        return new Msg(Role.TOOL, content, toolUseId);
    }
}
