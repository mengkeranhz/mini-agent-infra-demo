package org.platform.demo;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.platform.kernel.Receipt;
import org.platform.kernel.id.IdempotencyKey;
import org.platform.kernel.port.ReceiptStore;

/**
 * 收据的内存实现：ConcurrentHashMap.putIfAbsent 保证同键只一个收据胜出。
 * 进程内有效、进程死了就没了——生产要换 DB 唯一键（UNIQUE(tenant, run, tool_call) + INSERT ON CONFLICT DO NOTHING）。
 */
public final class MemoryReceiptStore implements ReceiptStore {

    private final ConcurrentHashMap<IdempotencyKey, Receipt> receipts = new ConcurrentHashMap<>();

    @Override
    public Optional<Receipt> find(IdempotencyKey key) {
        return Optional.ofNullable(receipts.get(key));
    }

    @Override
    public boolean trySaveIfAbsent(IdempotencyKey key, Receipt receipt) {
        return receipts.putIfAbsent(key, receipt) == null;
    }
}
