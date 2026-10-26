package org.platform.kernel.port;

import java.util.Optional;

import org.platform.kernel.id.RunId;
import org.platform.kernel.id.TenantId;

/**
 * artifact 仓：大结果的全文存放处（十二步的第 10 步）。
 * 轨迹里只留引用，上下文不被大结果灌爆；引用落在 (tenant, run) 名下，别家的引用查不到。
 */
public interface ArtifactStore {

    /** 存全文，返回引用——这个串会进轨迹，必须稳定可回查。 */
    String save(TenantId tenantId, RunId runId, String toolUseId, String content);

    /** 按引用取全文：引用不在 (tenant, run) 名下就查不到——隔离与调用侧同一套规矩。 */
    Optional<String> load(TenantId tenantId, RunId runId, String artifactRef);
}
