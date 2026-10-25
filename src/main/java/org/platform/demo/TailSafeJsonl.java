package org.platform.demo;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** JSONL 尾部纪律：'\n' 是一行的提交标志，写到一半的残行不算有效事件，重开时截掉。FileJournal / FileReceiptStore 共用。 */
final class TailSafeJsonl {

    private TailSafeJsonl() {
    }

    /** 截掉不以 '\n' 结尾的尾部残行——哪怕残行恰好是合法 JSON，没等到换行就是没提交。 */
    static void discardBrokenTail(Path file) throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        byte[] all = Files.readAllBytes(file);
        int lastNl = lastIndexOf(all, (byte) '\n');
        if (all.length == 0 || lastNl == all.length - 1) {
            return;
        }
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ch.truncate(lastNl < 0 ? 0 : lastNl + 1);
        }
    }

    static int lastIndexOf(byte[] bytes, byte target) {
        for (int i = bytes.length - 1; i >= 0; i--) {
            if (bytes[i] == target) {
                return i;
            }
        }
        return -1;
    }
}
