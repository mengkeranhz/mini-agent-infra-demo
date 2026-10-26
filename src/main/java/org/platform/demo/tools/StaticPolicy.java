package org.platform.demo.tools;

import java.util.Set;

import org.platform.kernel.port.Decision;
import org.platform.kernel.port.PolicyEngine;
import org.platform.kernel.port.Principal;
import org.platform.kernel.port.Scope;

/**
 * 静态规则策略（demo）：机器必须持 INVOKE——scope 是机器的授权面，没它连发起都不行；
 * 工具名进 deny 集合拒、进 ask 集合升级给人批、其余放行。
 * Human 按角色授权是后续里程碑的事，本实现对人一律放行——demo 只有机器走网关，别当生产策略用。
 */
public final class StaticPolicy implements PolicyEngine {

    private final Set<String> askTools;
    private final Set<String> denyTools;

    private StaticPolicy(Set<String> askTools, Set<String> denyTools) {
        this.askTools = askTools;
        this.denyTools = denyTools;
    }

    /** 全放行（机器仍须持 INVOKE）。 */
    public static StaticPolicy allowAll() {
        return new StaticPolicy(Set.of(), Set.of());
    }

    /** 这些工具要人批（ASK），其余放行。 */
    public static StaticPolicy asking(String... toolNames) {
        return new StaticPolicy(Set.of(toolNames), Set.of());
    }

    /** 这些工具直接拒（DENY），其余放行。 */
    public static StaticPolicy denying(String... toolNames) {
        return new StaticPolicy(Set.of(), Set.of(toolNames));
    }

    @Override
    public Decision decide(Principal principal, String toolName, String inputJson) {
        if (principal instanceof Principal.Machine machine && !machine.scopes().contains(Scope.INVOKE)) {
            return Decision.DENY;
        }
        if (denyTools.contains(toolName)) {
            return Decision.DENY;
        }
        if (askTools.contains(toolName)) {
            return Decision.ASK;
        }
        return Decision.ALLOW;
    }
}
