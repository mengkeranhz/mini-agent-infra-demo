package org.platform.demo;

import java.util.List;

import org.platform.kernel.id.StepCtx;
import org.platform.kernel.port.ModelGateway;

/**
 * 模型替身：无状态，只按 messages 里已有的工具结果决策——没搜过 → 搜索，
 * 搜过没发过 → 发消息，都做过 → 终答。不看步数、不记轮次，跟真模型一样只认输入。
 */
public final class StubModelGateway implements ModelGateway {

    private static final String SEARCH_CALL = "t-1";
    private static final String SEND_CALL = "t-2";

    @Override
    public ModelResponse sample(StepCtx ctx, List<Msg> messages) {
        boolean searched = messages.stream().anyMatch(m -> SEARCH_CALL.equals(m.toolUseId()));
        boolean sent = messages.stream().anyMatch(m -> SEND_CALL.equals(m.toolUseId()));
        if (!searched) {
            return new ModelResponse(
                    "先搜索。",
                    List.of(new ToolUse(SEARCH_CALL, "web_search", """
                            {"query":"agent kernel"}""")));
        }
        if (!sent) {
            // campaign 带上租户：下游的外层幂等键（to/campaign/seq）看不到平台上下文，
            // 业务键不带租户就会跨租户撞车——别家先发过同名 campaign，本家的消息会被下游当重放吞掉
            return new ModelResponse(
                    "搜到结果了，发条消息收尾。",
                    List.of(new ToolUse(SEND_CALL, "send_message", """
                            {"to":"ops","campaign":"%s","seq":1,"msg":"hello kernel"}""".formatted("m1-" + ctx.tenantId().value()))));
        }
        return ModelResponse.of("消息已发出，run 结束。");
    }
}
