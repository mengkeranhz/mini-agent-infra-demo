package org.platform.kernel.port;

import java.util.List;

import org.platform.demo.ModelResponse;
import org.platform.demo.Msg;
import org.platform.kernel.JournalEvent;
import org.platform.kernel.id.StepCtx;

/**
 * 模型网关：kernel 只面向这个接缝编程，采样能力由基座注入（API 客户端、脚本桩皆可）。
 * 协议类型暂在 demo 包，后续上移时只需改这里的 import。
 */
public interface ModelGateway {

    /** 一步模型采样：给定当前 messages，返回模型输出（文本 + 可能的 tool_use + usage）。 */
    ModelResponse sample(StepCtx ctx, List<Msg> messages);
}
