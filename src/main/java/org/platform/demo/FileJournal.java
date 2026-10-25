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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.platform.kernel.JournalEvent;
import org.platform.kernel.JournalEvent.RunFinished;
import org.platform.kernel.JournalEvent.RunStarted;
import org.platform.kernel.JournalEvent.StepDispatched;
import org.platform.kernel.JournalEvent.StepDone;
import org.platform.kernel.JournalEvent.StepFailed;
import org.platform.kernel.JournalEvent.StepPlanned;
import org.platform.kernel.port.Journal;
import org.platform.kernel.JournalEvent.StepResolution;

/**
 * journal 落盘实现：JSONL 追加写，一行一条事件；每条 write 后 force(true)，落盘才算提交。
 * '\n' 是一行的提交标志：write 写到一半崩溃会留下没有换行的残行，
 * 重新打开时截掉残行、不当有效事件，seq 从残行前继续。
 * 线格式 {"kind":"...","event":{...}}，kind 与类型的映射归本类管，kernel 不依赖序列化器。
 */
public final class FileJournal implements Journal {

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private final FileChannel channel;
    private long nextSeq;

    public FileJournal(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        TailSafeJsonl.discardBrokenTail(file);
        this.nextSeq = countLines();
        this.channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @Override
    public long append(JournalEvent event) {
        try {
            ObjectNode line = mapper.createObjectNode();
            line.put("kind", event.getClass().getSimpleName());
            line.set("event", mapper.valueToTree(event));
            ByteBuffer buf = ByteBuffer.wrap((mapper.writeValueAsString(line) + "\n").getBytes(StandardCharsets.UTF_8));
            while (buf.hasRemaining()) {
                channel.write(buf);
            }
            channel.force(true);
        } catch (IOException e) {
            throw new UncheckedIOException("journal append 失败: " + file, e);
        }
        return nextSeq++;
    }

    @Override
    public List<JournalEvent> scan() {
        try {
            List<JournalEvent> events = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    JsonNode root = mapper.readTree(line);
                    events.add(mapper.treeToValue(root.path("event"), typeFor(root.path("kind").asText())));
                }
            }
            return events;
        } catch (IOException e) {
            throw new UncheckedIOException("journal scan 失败: " + file, e);
        }
    }

    private static Class<? extends JournalEvent> typeFor(String kind) {
        return switch (kind) {
            case "RunStarted" -> RunStarted.class;
            case "StepPlanned" -> StepPlanned.class;
            case "StepDispatched" -> StepDispatched.class;
            case "StepDone" -> StepDone.class;
            case "StepFailed" -> StepFailed.class;
            case "StepResolution" -> StepResolution.class;
            case "RunFinished" -> RunFinished.class;
            default -> throw new IllegalStateException("未知事件类型: " + kind);
        };
    }

    private long countLines() throws IOException {
        if (!Files.exists(file)) {
            return 0;
        }
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream().filter(l -> !l.isBlank()).count();
    }

}
