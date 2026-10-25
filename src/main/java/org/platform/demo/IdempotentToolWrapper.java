package org.platform.demo;

import java.util.concurrent.ConcurrentHashMap;

import org.platform.kernel.Receipt;
import org.platform.kernel.id.IdempotencyKey;
import org.platform.kernel.id.StepCtx;
import org.platform.kernel.port.ReceiptStore;
import org.platform.kernel.port.ToolGateway;

/**
 * 幂等层：包在 ToolGateway 外面的装饰器，与路由层分离——网关照旧路由，幂等只管「同键只执行一次」。
 * 键 = (tenantId, runId, toolUseId)：命中收据直接回放；并发抢输（trySaveIfAbsent=false）丢弃自己的结果、改用已有的。
 */
public final class IdempotentToolWrapper implements ToolGateway {

    private final ToolGateway inner;
    private final ReceiptStore receipts;
    /** 同键串行化的进程内锁：find→invoke→save 是 check-then-act，不加锁并发同键会双执行；跨进程要换 DB claim 行 + 等待协议。 */
    private final ConcurrentHashMap<IdempotencyKey, Object> locks = new ConcurrentHashMap<>();

    /**
     * 契约：这里的 receipts 必须与注入 AgentLoop 的是同一个实例（装配处保证）——
     * 幂等层存下的收据要能被恢复裁决查到，两个仓就断了这条线。
     */
    public IdempotentToolWrapper(ToolGateway inner, ReceiptStore receipts) {
        this.inner = inner;
        this.receipts = receipts;
    }

    /** 同键一次执行：先查收据，没有才真正调用，调完立刻抢存收据。 */
    @Override
    public ToolResult invoke(StepCtx ctx, ToolUse call) {
        IdempotencyKey key = new IdempotencyKey(ctx.tenantId(), ctx.runId(), call.id());
        synchronized (locks.computeIfAbsent(key, k -> new Object())) {
            Receipt hit = receipts.find(key).orElse(null);
            if (hit != null) {
                return replayHit(call.id(), hit);
            }
            return executeAndStore(ctx, call, key);
        }
    }

    /** 收据在手：不执行，原样回放。 */
    private static ToolResult replayHit(String toolUseId, Receipt hit) {
        System.out.println("REPLAY " + toolUseId + " 收据命中，不重复执行: " + hit.content());
        return new ToolResult(toolUseId, hit.content(), hit.isError());
    }

    /** 执行一次并把收据抢存；并发抢输（同键收据已有人存）就丢自己的结果、用赢家的。 */
    private ToolResult executeAndStore(StepCtx ctx, ToolUse call, IdempotencyKey key) {
        ToolResult result = inner.invoke(ctx, call);
        // 收据要「执行返回后立刻」抢存：窗口 [Dispatched, 收据落库) 内崩溃 = 执行过但没有收据，
        // 恢复时只能按 StepPlanned 的 idempotent 标记裁决（幂等重跑 / 非幂等绝不重放）
        if (!receipts.trySaveIfAbsent(key, new Receipt(result.content(), result.isError()))) {
            Receipt winner = receipts.find(key).orElseThrow();
            System.out.println("REPLAY " + key.toolUseId() + " 并发抢输，采用已有收据: " + winner.content());
            return new ToolResult(call.id(), winner.content(), winner.isError());
        }
        return result;
    }

    @Override
    public boolean idempotent(String toolName) {
        return inner.idempotent(toolName);
    }
}
