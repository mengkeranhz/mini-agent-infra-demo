package org.platform.kernel.port;

import java.util.List;

import org.platform.kernel.JournalEvent;

/** run 的事实来源：只追加、不可改，(runId, seq) 主键；崩溃恢复与重放裁决都以它为准。 */
public interface Journal {

    /** 追加一条事件并落盘（force），返回分配到的单调 seq。 */
    long append(JournalEvent event);

    /** 按写入顺序读回全部事件——重放的输入。 */
    List<JournalEvent> scan();
}
