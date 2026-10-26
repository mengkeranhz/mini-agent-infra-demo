package org.platform.kernel;

import java.time.Instant;
import java.util.Objects;

import org.platform.kernel.id.RunId;
import org.platform.kernel.id.TenantId;
import org.platform.kernel.port.Principal;

/**
 * 审批凭证（七字段纪律）：toolName / toolVersion / inputDigest / targetResource /
 * tenantId / runId / toolUseId 唯一定位并呈现「要对什么资源做什么」——
 * 网关第 6 步逐一核对，一处不符即作废：批条批的是这一次调用，不是这类调用。
 * expiresAt：批条会陈旧，过期的批条等于没有批条。
 * approver 只能是 Principal.Human——类型即闸门：机器的 Scope 里没有 APPROVE，
 * 机器构造不出这个字段，自我审批在编译期就被堵死。
 */
public record ApprovalRecord(String toolName, String toolVersion, String inputDigest,
                             String targetResource, TenantId tenantId, RunId runId,
                             String toolUseId, Instant expiresAt, Principal.Human approver) {

    public ApprovalRecord {
        requireText(toolName, "toolName");
        requireText(toolVersion, "toolVersion");
        requireText(inputDigest, "inputDigest");
        requireText(targetResource, "targetResource");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(runId, "runId");
        RunId.requireValid(toolUseId, "toolUseId");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(approver, "approver");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("ApprovalRecord." + name + " 不能为空");
        }
    }
}
