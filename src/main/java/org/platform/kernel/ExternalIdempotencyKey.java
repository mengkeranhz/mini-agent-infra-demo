package org.platform.kernel;

import java.util.Objects;

/**
 * 外层幂等键：跨系统的幂等凭证。平台内那层（IdempotencyKey）只保平台自己不重发；
 * 这层让下游系统自己认出「这单我见过」——平台崩了、换了实现，下游还站得住。
 * system = 下游系统名；operation = 操作名；businessKey = 业务去重键（通常是对内容的摘要）。
 */
public record ExternalIdempotencyKey(String system, String operation, String businessKey) {

    public ExternalIdempotencyKey {
        Objects.requireNonNull(system, "system");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(businessKey, "businessKey");
        if (system.isBlank() || operation.isBlank() || businessKey.isBlank()) {
            throw new IllegalArgumentException("外层幂等键三段都不许为空: " + system + "/" + operation);
        }
    }
}
