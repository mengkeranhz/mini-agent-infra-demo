package org.platform.kernel.port;

import java.time.Instant;

import org.platform.kernel.id.RunId;
import org.platform.kernel.id.TenantId;

/**
 * 审计日志：网关每个出口留一条痕——放行、拒绝、回放 alike（十二步的第 11 步）。
 * Event 与 ApprovalRecord 同款七字段纪律，前七个组件顺序与批条一字不差；
 * 另带谁（principal）、何时（at）、结果如何（outcome + detail）。
 * outcome 取值（字符串常量，命名与 journal 的 source 对齐）：
 * unknown_tool / unreleased_version / schema_rejected / policy_denied /
 * awaiting_approval / executed / replayed_from_receipt。
 * unknown_tool 时 toolVersion / targetResource 为 null——工具都没查到，没有版本可言。
 */
public interface AuditLog {

    record Event(String toolName, String toolVersion, String inputDigest, String targetResource,
                 TenantId tenantId, RunId runId, String toolUseId,
                 Instant at, Principal principal, String outcome, String detail) {
    }

    void append(Event event);
}
