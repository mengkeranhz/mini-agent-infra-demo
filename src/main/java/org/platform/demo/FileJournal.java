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
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.platform.kernel.JournalEvent;
import org.platform.kernel.JournalEvent.RunWaiting;
import org.platform.kernel.JournalEvent.RunFinished;
import org.platform.kernel.JournalEvent.RunStarted;
import org.platform.kernel.JournalEvent.StepDispatched;
import org.platform.kernel.JournalEvent.StepDone;
import org.platform.kernel.JournalEvent.StepFailed;
import org.platform.kernel.JournalEvent.StepPlanned;
import org.platform.kernel.JournalEvent.StepResolution;
import org.platform.kernel.id.RunId;
import org.platform.kernel.id.TenantId;
import org.platform.kernel.port.Journal;

/**
 * journal 落盘实现：JSONL 追加写，一行一条事件；每条 write 后 force(true)，落盘才算提交。
 * 隔离先做在文件布局上：一个 (tenant, run) 一个独立文件 baseDir/&lt;tenant&gt;/&lt;run&gt;.jsonl——
 * scan(tenant, run, afterSeq) 只打开调用方自己租户的文件，别家的事件根本不进内存，
 * 猜中 runId 也只能撞见一个空文件。单库混写 + WHERE tenant/run 过滤的形态留给 PG 实现。
 * '\n' 是一行的提交标志：write 写到一半崩溃会留下没有换行的残行，
 * 重新打开时截掉残行、不当有效事件，seq 从残行前继续（seq 按 (tenant, run) 文件各自计数）。
 * 线格式 {"kind":"...","event":{...}}，kind 与类型的映射归本类管，kernel 不依赖序列化器。
 */
public final class FileJournal implements Journal {

    private final Path baseDir;
    private final ObjectMapper mapper = new ObjectMapper();
    /** 每个 (tenant, run) 文件一个 channel + 一份 seq 计数；单写者纪律，append 由 run 主循环串行调用。 */
    private final Map<Path, FileChannel> channels = new ConcurrentHashMap<>();
    private final Map<Path, Long> nextSeqByFile = new ConcurrentHashMap<>();

    public FileJournal(Path baseDir) {
        this.baseDir = baseDir;
    }

    @Override
    public long append(JournalEvent event) {
        Path file = fileOf(event.tenantId(), event.runId());
        try {
            FileChannel channel = openForAppend(file);
            long seq = nextSeqByFile.get(file);
            ObjectNode line = mapper.createObjectNode();
            line.put("kind", event.getClass().getSimpleName());
            line.set("event", mapper.valueToTree(event));
            ByteBuffer buf = ByteBuffer.wrap((mapper.writeValueAsString(line) + "\n").getBytes(StandardCharsets.UTF_8));
            while (buf.hasRemaining()) {
                channel.write(buf);
            }
            channel.force(true);
            nextSeqByFile.put(file, seq + 1);
            return seq;
        } catch (IOException e) {
            throw new UncheckedIOException("journal append 失败: " + file, e);
        }
    }

    @Override
    public List<JournalEvent> scan(TenantId tenantId, RunId runId, long afterSeq) {
        Path file = fileOf(tenantId, runId);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            // 打开顺带执行尾部纪律：残行不截掉，readAllLines 会把半行当一条事件读回来
            openForAppend(file);
            List<JournalEvent> events = new ArrayList<>();
            long seq = -1;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                seq++;
                if (seq <= afterSeq) {
                    continue;
                }
                JsonNode root = mapper.readTree(line);
                JournalEvent event = mapper.treeToValue(root.path("event"), typeFor(root.path("kind").asText()));
                // 文件已按 (tenant, run) 划开，内容再验一道：被搬错文件、手工并库的事件不放行
                if (!event.tenantId().equals(tenantId) || !event.runId().equals(runId)) {
                    throw new IllegalStateException("journal 事件与 (tenant, run) 不符: " + file + " -> " + line);
                }
                events.add(event);
            }
            return events;
        } catch (IOException e) {
            throw new UncheckedIOException("journal scan 失败: " + file, e);
        }
    }

    /** tenant/run 已过 requireValid（禁路径分隔符与裸点号），直接当路径成分是安全的。 */
    private Path fileOf(TenantId tenantId, RunId runId) {
        return baseDir.resolve(tenantId.value()).resolve(runId.value() + ".jsonl");
    }

    /** 惰性打开：建目录、截残行、seq 从现有行数续。同一文件只开一个 channel。 */
    private synchronized FileChannel openForAppend(Path file) throws IOException {
        FileChannel channel = channels.get(file);
        if (channel != null && channel.isOpen()) {
            return channel;
        }
        if (Files.exists(file)) {
            TailSafeJsonl.discardBrokenTail(file);
            nextSeqByFile.put(file, countLines(file));
        } else {
            Files.createDirectories(file.getParent());
            nextSeqByFile.put(file, 0L);
        }
        channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        channels.put(file, channel);
        return channel;
    }

    private static Class<? extends JournalEvent> typeFor(String kind) {
        return switch (kind) {
            case "RunStarted" -> RunStarted.class;
            case "StepPlanned" -> StepPlanned.class;
            case "StepDispatched" -> StepDispatched.class;
            case "StepDone" -> StepDone.class;
            case "StepFailed" -> StepFailed.class;
            case "StepResolution" -> StepResolution.class;
            case "RunWaiting" -> RunWaiting.class;
            case "RunFinished" -> RunFinished.class;
            default -> throw new IllegalStateException("未知事件类型: " + kind);
        };
    }

    private static long countLines(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream().filter(l -> !l.isBlank()).count();
    }

}
