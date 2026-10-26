package org.platform.kernel.port;

import java.util.List;

import org.platform.kernel.JournalEvent;
import org.platform.kernel.id.RunId;
import org.platform.kernel.id.TenantId;

/**
 * run 的事实来源：只追加、不可改，(tenantId, runId, seq) 主键；崩溃恢复与重放裁决都以它为准。
 * 隔离是端口契约，不是实现细节：scan 只回答「这个租户名下这个 run」的事件，
 * runId 不带租户维度、撞上不稀奇——实现方不得因为 runId 匹配就把别家的事件送出端口。
 */
public interface Journal {

    /**
     * 追加一条事件并落盘（force），返回分配到的单调 seq。
     * 事件的 tenantId 即归属，实现方必须原样落盘、不得改写。
     * 契约：seq 仅在同一个 (tenantId, runId) 内单调；多实例/多进程各自计数，
     * 全局唯一性靠 (tenantId, runId, seq) 主键 + 单写者纪律，不靠这个返回值。
     */
    long append(JournalEvent event);

    /**
     * 按写入顺序读回 (tenantId, runId) 名下、seq 严格大于 afterSeq 的事件（afterSeq 传 -1 表示从头）。
     * 别的租户、别的 run 的事件一个都不许出现在返回值里——这不是性能优化，是隔离边界。
     */
    List<JournalEvent> scan(TenantId tenantId, RunId runId, long afterSeq);
}
