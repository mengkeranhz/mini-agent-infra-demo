package org.platform.demo.tools;

import java.util.concurrent.ConcurrentHashMap;

import org.platform.kernel.ExternalIdempotencyKey;

/**
 * 下游替身（邮件箱）：按外层幂等键去重——同一个 (system, operation, businessKey) 只投递一次。
 * 状态在下游自己的地盘上，不依赖平台任何组件：平台幂等层崩了、换了实现，这里照样站得住。
 */
public final class FakeMailbox {

    private final ConcurrentHashMap<ExternalIdempotencyKey, String> delivered = new ConcurrentHashMap<>();

    /** 投递一封信；重复键不投，返回 DUPLICATE-IGNORED。 */
    public String deliver(ExternalIdempotencyKey key, String msg) {
        String prev = delivered.putIfAbsent(key, msg);
        if (prev != null) {
            System.out.println("DUPLICATE-IGNORED: " + msg);
            return "DUPLICATE-IGNORED";
        }
        System.out.println("DELIVERED: " + msg);
        return "ok";
    }
}
