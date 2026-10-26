package org.platform.demo.tools;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.platform.kernel.id.TenantId;

/**
 * 工具注册表：M2 接管注册期把关（ToolRouter 的那道闸移到这里）——
 * 非幂等工具必须提供外层幂等键，否则拒绝注册：副作用工具不认键，下游裸奔。
 * 一个名字一条注册：重名即拒，防运行中被偷换实现——换版本走重新发布，不走同名覆盖。
 * 租户集合显式给：不设通配租户，注册时就得想清楚给谁用（与 TenantId 不设 DEFAULT 同一个道理）。
 */
public final class ToolRegistry {

    /** 注册项：工具本体 + 钉死的版本 + 可用租户 + 目标资源 + 必填字段 + 是否已发布。 */
    public record Registration(Tool tool, String toolVersion, Set<TenantId> tenants,
                               String targetResource, Set<String> requiredInputFields, boolean released) {
    }

    /** 租户视图（网关第 1 步的载体）：视图里只有本租户可用的工具，跨租户连存在性都看不见。 */
    public record View(Map<String, Registration> tools) {

        public Optional<Registration> find(String toolName) {
            return Optional.ofNullable(tools.get(toolName));
        }
    }

    private final Map<String, Registration> tools = new HashMap<>();

    /** 注册一个工具，返回 this 供链式拼装。 */
    public ToolRegistry register(Tool tool, String toolVersion, Set<TenantId> tenants,
                                 String targetResource, Set<String> requiredInputFields, boolean released) {
        if (!tool.idempotent() && !tool.providesExternalKey()) {
            throw new IllegalArgumentException("拒绝注册 " + tool.name()
                    + "：非幂等工具必须提供外层幂等键——副作用工具不认键，下游裸奔");
        }
        if (tools.containsKey(tool.name())) {
            throw new IllegalArgumentException("拒绝注册 " + tool.name()
                    + "：同名工具已注册——一个名字一条注册，防偷换");
        }
        if (toolVersion == null || toolVersion.isBlank()) {
            throw new IllegalArgumentException("拒绝注册 " + tool.name() + "：版本不能为空");
        }
        if (targetResource == null || targetResource.isBlank()) {
            throw new IllegalArgumentException("拒绝注册 " + tool.name() + "：targetResource 不能为空");
        }
        if (tenants.isEmpty()) {
            throw new IllegalArgumentException("拒绝注册 " + tool.name()
                    + "：租户集合不能为空——不给任何租户用的注册，多半是装配错了");
        }
        tools.put(tool.name(), new Registration(tool, toolVersion, Set.copyOf(tenants), targetResource,
                Set.copyOf(requiredInputFields), released));
        return this;
    }

    /** 本租户可用的工具视图——租户检查先于一切，跨租户探测与「不存在」同音。 */
    public View forTenant(TenantId tenant) {
        Map<String, Registration> visible = new HashMap<>();
        for (Map.Entry<String, Registration> e : tools.entrySet()) {
            if (e.getValue().tenants().contains(tenant)) {
                visible.put(e.getKey(), e.getValue());
            }
        }
        return new View(Map.copyOf(visible));
    }

    /** 按名查幂等性（harness 写 StepPlanned 用）：走租户视图，与网关第 1 步同一口径——
     *  本租户查无此工具（别家的、不存在的）按非幂等处理，宁可保守。 */
    public boolean idempotent(TenantId tenant, String toolName) {
        return forTenant(tenant).find(toolName).map(r -> r.tool().idempotent()).orElse(false);
    }
}
