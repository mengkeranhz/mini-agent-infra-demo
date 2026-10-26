package org.platform.demo;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.platform.kernel.port.AuditLog;

/** 内存审计日志：demo/测试用，append 顺序全量保留——M5 的正式审计从这里换实现。 */
public final class MemoryAuditLog implements AuditLog {

    private final List<Event> events = new CopyOnWriteArrayList<>();

    @Override
    public void append(Event event) {
        events.add(event);
    }

    /** 全部审计事件，按 append 顺序。 */
    public List<Event> events() {
        return List.copyOf(events);
    }
}
