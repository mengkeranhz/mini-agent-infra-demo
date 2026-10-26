package org.platform.demo;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.platform.kernel.id.RunId;
import org.platform.kernel.id.TenantId;
import org.platform.kernel.port.ArtifactStore;

/**
 * 内存 artifact 仓：引用 = tenant/run/toolUseId——三者都是受约束的路径安全成分，拼起来天然唯一且可校验。
 * 同一调用只有一份全文：幂等层保证同键只执行一次，put 覆盖也只是幂等重写。
 */
public final class MemoryArtifactStore implements ArtifactStore {

    private final Map<String, String> byRef = new ConcurrentHashMap<>();

    @Override
    public String save(TenantId tenantId, RunId runId, String toolUseId, String content) {
        String ref = ref(tenantId, runId, toolUseId);
        byRef.put(ref, content);
        return ref;
    }

    @Override
    public Optional<String> load(TenantId tenantId, RunId runId, String artifactRef) {
        if (!artifactRef.startsWith(tenantId.value() + "/" + runId.value() + "/")) {
            return Optional.empty();   // 别家的引用：查无此物，不报错——不泄露存在性
        }
        return Optional.ofNullable(byRef.get(artifactRef));
    }

    private static String ref(TenantId tenantId, RunId runId, String toolUseId) {
        return tenantId.value() + "/" + runId.value() + "/" + toolUseId;
    }
}
