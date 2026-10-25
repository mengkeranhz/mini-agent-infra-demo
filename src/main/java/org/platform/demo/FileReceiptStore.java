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
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.platform.kernel.Receipt;
import org.platform.kernel.id.IdempotencyKey;
import org.platform.kernel.port.ReceiptStore;

/**
 * 收据落盘实现：journal 的同一套纪律——一行一条、写后 force(true)、'\n' 是提交标志、重开截残行。
 * find() 全量扫文件（文件小，够用）；trySaveIfAbsent() 先扫判存在再写——这里同样是 check-then-act，
 * 进程内用同一把锁兜住，跨进程靠 DB 唯一键（UNIQUE(tenant, run, tool_call) + INSERT ON CONFLICT DO NOTHING）。
 */
public final class FileReceiptStore implements ReceiptStore {

    private record Line(IdempotencyKey key, Receipt receipt) {
    }

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private final FileChannel channel;

    public FileReceiptStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        FileJournal.discardBrokenTail(file);
        this.channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @Override
    public Optional<Receipt> find(IdempotencyKey key) {
        return scan().stream().filter(line -> line.key().equals(key)).map(Line::receipt).findFirst();
    }

    @Override
    public boolean trySaveIfAbsent(IdempotencyKey key, Receipt receipt) {
        synchronized (this) {
            if (find(key).isPresent()) {
                return false;
            }
            append(key, receipt);
            return true;
        }
    }

    private void append(IdempotencyKey key, Receipt receipt) {
        try {
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

    /** 全量读回；尾部残行（没等到换行）不算数，中部损坏直接炸。 */
    private List<Line> scan() {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
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
}
