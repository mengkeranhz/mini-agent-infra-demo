package org.platform.kernel.id;

import java.util.Objects;

/** 工具调用的幂等键：tenant + run + toolUseId 三元组唯一定位一次调用。 */
public record IdempotencyKey(TenantId tenantId, RunId runId, String toolUseId) {

    public IdempotencyKey {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(runId, "runId");
        RunId.requireValid(toolUseId, "toolUseId");
    }
}
