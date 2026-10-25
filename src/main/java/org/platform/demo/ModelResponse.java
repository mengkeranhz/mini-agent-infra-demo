package org.platform.demo;

import java.util.List;

/** 一次模型采样的输出：文本 + 若干工具调用 + token 用量；没有 tool_use 即 run 结束。 */
public record ModelResponse(String text, List<ToolUse> toolUses, Usage usage) {

    public static ModelResponse of(String text) {
        return new ModelResponse(text, List.of(), Usage.ZERO);
    }

    /** 桩场景不关心用量；真实网关走三参构造带 usage。 */
    public ModelResponse(String text, List<ToolUse> toolUses) {
        this(text, toolUses, Usage.ZERO);
    }

    public boolean hasToolUse() {
        return !toolUses.isEmpty();
    }
}