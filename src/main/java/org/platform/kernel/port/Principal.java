package org.platform.kernel.port;

import java.util.Set;

import org.platform.kernel.id.RunId;

/**
 * 调用方身份：人与机器是两个类型，不是一个类型加个 flag——
 * 授权模型跟着类型走（Human 按角色、Machine 按 scope），
 * 混在一个类型里，迟早长出「机器带个 isHuman=false 却持有审批权」的口子。
 * sealed：身份只有这两种；出现第三种身份时再显式扩 permits，别偷偷加实现类。
 */
public sealed interface Principal {

    /** 人：按角色授权。审批、悬空步裁决这类动作只认 Human——机器的 Scope 里没有 APPROVE。 */
    record Human(String userId, Set<String> roles) implements Principal {

        public Human {
            RunId.requireValid(userId, "userId");
            roles = Set.copyOf(roles);
        }
    }

    /** 机器：按 scope 授权。credentialId 是凭证标识不是人——拿它当 userId，就是把机器当人。 */
    record Machine(String credentialId, Set<Scope> scopes) implements Principal {

        public Machine {
            RunId.requireValid(credentialId, "credentialId");
            scopes = Set.copyOf(scopes);
        }
    }
}
