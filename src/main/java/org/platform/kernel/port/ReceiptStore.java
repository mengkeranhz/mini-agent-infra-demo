package org.platform.kernel.port;

import java.util.Optional;

import org.platform.kernel.Receipt;
import org.platform.kernel.id.IdempotencyKey;

/** 工具执行收据的存取：幂等层的「执行过没有」事实来源。 */
public interface ReceiptStore {

    /** 查收据：命中说明同键调用已执行过，直接回放，别再执行。 */
    Optional<Receipt> find(IdempotencyKey key);

    /**
     * 无则存入、返回 true；已有人存过返回 false——并发抢输了，
     * 自己的结果必须丢弃，改用 find(key) 拿到的那份。
     */
    boolean trySaveIfAbsent(IdempotencyKey key, Receipt receipt);
}
