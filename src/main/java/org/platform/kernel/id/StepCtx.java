package org.platform.kernel.id;

/** 一步执行的上下文：tenant + run + 步定位随身带，网关据此定位 journal 与幂等键。 */
public record StepCtx(TenantId tenantId, RunId runId, StepId stepId) {
}
