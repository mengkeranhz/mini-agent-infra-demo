package org.platform.kernel.port;

import org.platform.kernel.id.RunId;

/**
 * 基座与 harness 的唯一契约：一次 run 的完整驱动。
 * 基础设施（模型网关、工具网关、journal）由 harness 构造器显式注入，kernel 不感知注入方式；
 * run(...) 只带 run 级参数。是否可跨 run 复用由实现是否有状态决定，契约不做承诺。
 */
public interface HarnessAdapter {

    /** 跑完一个任务：采样 → 执行工具 → 写 journal，直到模型不再要工具，返回最终回答。 */
    String run(RunId runId, String task);
}
