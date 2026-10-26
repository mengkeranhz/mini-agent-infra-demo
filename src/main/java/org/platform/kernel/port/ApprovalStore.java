package org.platform.kernel.port;

import java.util.Optional;

import org.platform.kernel.ApprovalRecord;
import org.platform.kernel.id.RunId;
import org.platform.kernel.id.TenantId;

/**
 * 审批仓：人工批条的存取。键是调用的完整身份 (tenant, run, toolUseId)——
 * 批条只对一次调用有效，换输入、换工具、换 run 都得重新批（网关第 6 步逐字段核对）。
 */
public interface ApprovalStore {

    /** 查批条：没有就是没批，不猜。 */
    Optional<ApprovalRecord> find(TenantId tenantId, RunId runId, String toolUseId);

    /** 无则存入返回 true；同身份已有批条返回 false——先到先得，想改判走新调用。 */
    boolean trySaveIfAbsent(ApprovalRecord record);
}
