package org.platform.demo.tools;

import org.platform.demo.ToolResult;
import org.platform.demo.ToolUse;

/** 幂等工具替身：sleep 500ms 后返回固定文本，重放多少次结果都一样。 */
public final class WebSearchTool implements Tool {

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public boolean idempotent() {
        return true;
    }

    @Override
    public ToolResult invoke(ToolUse use) {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ToolResult(use.id(), "interrupted", true);
        }
        return new ToolResult(use.id(), "搜索完成，找到 3 篇相关文章：Agent 内核是什么、工具幂等性、崩溃后如何用 journal 恢复", false);
    }
}