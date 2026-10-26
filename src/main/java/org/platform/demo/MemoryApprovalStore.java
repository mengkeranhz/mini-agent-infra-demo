package org.platform.demo;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.platform.kernel.ApprovalRecord;
import org.platform.kernel.id.IdempotencyKey;
import org.platform.kernel.id.RunId;
import org.platform.kernel.id.TenantId;
import org.platform.kernel.port.ApprovalStore;

/** 内存审批仓：demo/测试用，重启即失——审批要跨重启，得等落盘实现（与收据仓同款套路）。 */
public final class MemoryApprovalStore implements ApprovalStore {

    private final Map<IdempotencyKey, ApprovalRecord> byCall = new ConcurrentHashMap<>();

    @Override
    public Optional<ApprovalRecord> find(TenantId tenantId, RunId runId, String toolUseId) {
        return Optional.ofNullable(byCall.get(new IdempotencyKey(tenantId, runId, toolUseId)));
    }

    @Override
    public boolean trySaveIfAbsent(ApprovalRecord record) {
        IdempotencyKey key = new IdempotencyKey(record.tenantId(), record.runId(), record.toolUseId());
        return byCall.putIfAbsent(key, record) == null;
    }
}
