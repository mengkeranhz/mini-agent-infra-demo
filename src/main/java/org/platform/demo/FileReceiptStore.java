package org.platform.demo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.platform.kernel.Receipt;
import org.platform.kernel.id.IdempotencyKey;
import org.platform.kernel.id.TenantId;
import org.platform.kernel.port.ReceiptStore;

/**
 * 收据落盘实现：journal 的同一套纪律——一行一条、写后 force(true)、'\n' 是提交标志、重开截残行。
 * 隔离同样落在布局上：一个 tenant 一个文件 baseDir/&lt;tenant&gt;/receipts.jsonl，
 * find/trySave 只碰键里那个租户的文件——别家的收据行连读都不读，正文不见面、元数据也不见面。
 * trySaveIfAbsent() 先扫判存在再写——这里同样是 check-then-act，
 * 进程内按 Path intern 的锁互斥（多个实例写同一文件也只一把锁），跨进程靠 DB 唯一键
 * （UNIQUE(tenant, run, tool_call) + INSERT ON CONFLICT DO NOTHING）。
 */
public final class FileReceiptStore implements ReceiptStore {

    /** 按 Path intern 的写锁：同一文件一个锁，不管进程里开了几个 store 实例。 */
    private static final ConcurrentHashMap<Path, Object> PATH_LOCKS = new ConcurrentHashMap<>();

    private record Line(IdempotencyKey key, Receipt receipt) {
    }

    private final Path baseDir;
    private final ObjectMapper mapper = new ObjectMapper();
    /** 每个 tenant 文件一个 channel；单写者纪律。 */
    private final Map<Path, FileChannel> channels = new ConcurrentHashMap<>();

    public FileReceiptStore(Path baseDir) {
        this.baseDir = baseDir;
    }

    @Override
    public Optional<Receipt> find(IdempotencyKey key) {
        return scan(key.tenantId()).stream()
                .filter(line -> line.key().equals(key))
                .map(Line::receipt)
                .findFirst();
    }

    @Override
    public boolean trySaveIfAbsent(IdempotencyKey key, Receipt receipt) {
        Path file = fileOf(key.tenantId());
        synchronized (PATH_LOCKS.computeIfAbsent(file.toAbsolutePath().normalize(), p -> new Object())) {
            if (find(key).isPresent()) {
                return false;
            }
            append(file, key, receipt);
            return true;
        }
    }

    private void append(Path file, IdempotencyKey key, Receipt receipt) {
        try {
            FileChannel channel = openForAppend(file);
            ByteBuffer buf = ByteBuffer.wrap(
                    (mapper.writeValueAsString(new Line(key, receipt)) + "\n").getBytes(StandardCharsets.UTF_8));
            while (buf.hasRemaining()) {
                channel.write(buf);
            }
            channel.force(true);
        } catch (IOException e) {
            throw new UncheckedIOException("收据落盘失败: " + file, e);
        }
    }

    /** 只读键所指租户的文件；尾部残行（没等到换行）不算数，中部损坏直接炸。 */
    private List<Line> scan(TenantId tenantId) {
        Path file = fileOf(tenantId);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            // 打开顺带截残行：半行不当有效收据
            openForAppend(file);
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<Line> parsed = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isBlank()) {
                    continue;
                }
                try {
                    parsed.add(mapper.readValue(line, Line.class));
                } catch (JsonProcessingException e) {
                    if (i == lines.size() - 1) {
                        return parsed;
                    }
                    throw new IllegalStateException("收据文件损坏: " + file + " -> " + line, e);
                }
            }
            return parsed;
        } catch (IOException e) {
            throw new UncheckedIOException("收据扫描失败: " + file, e);
        }
    }

    /** tenant 已过 requireValid（禁路径分隔符与裸点号），直接当目录名是安全的。 */
    private Path fileOf(TenantId tenantId) {
        return baseDir.resolve(tenantId.value()).resolve("receipts.jsonl");
    }

    /** 惰性打开：建目录、截残行。同一文件只开一个 channel。 */
    private synchronized FileChannel openForAppend(Path file) throws IOException {
        FileChannel channel = channels.get(file);
        if (channel != null && channel.isOpen()) {
            return channel;
        }
        if (Files.exists(file)) {
            TailSafeJsonl.discardBrokenTail(file);
        } else {
            Files.createDirectories(file.getParent());
        }
        channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        channels.put(file, channel);
        return channel;
    }
}
