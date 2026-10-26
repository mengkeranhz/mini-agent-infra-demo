package org.platform.demo.tools;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.platform.kernel.ExternalIdempotencyKey;

/**
 * 下游替身（邮件箱）：按外层幂等键去重。幂等契约：重复请求返回首次的真实结果，
 * 不是「已忽略」的占位符——重放必须语义等价于首次执行，模型两次看到的 content 才会一致。
 * 状态在下游自己的地盘上，不依赖平台任何组件。
 */
public final class FakeMailbox {

    private final ConcurrentHashMap<ExternalIdempotencyKey, String> results = new ConcurrentHashMap<>();
    private final AtomicInteger deliveredCount = new AtomicInteger();

    /** 投递一封信；同键重试不投，但返回首次投递的真实结果。 */
    public String deliver(ExternalIdempotencyKey key, String msg) {
        // computeIfAbsent 里才真投：首次执行返回什么就存什么，重放还的是首次存下的 content——
        // 占位符（"DUPLICATE-IGNORED" 之类）不行，重放必须 ≡ 首次执行，模型两次看到的 content 才一致
        boolean replay = results.containsKey(key);
        String content = results.computeIfAbsent(key, k -> {
            deliveredCount.incrementAndGet();
            System.out.println("DELIVERED: " + msg);
            return "ok";
        });
        if (replay) {
            System.out.println("MAILBOX-REPLAY: " + msg);
        }
        return content;
    }

    /** 真实投递次数——测试靠它分清「拦住双发的是平台层还是下游」。 */
    public int deliveredCount() {
        return deliveredCount.get();
    }
}
