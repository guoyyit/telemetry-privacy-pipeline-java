package com.github.guoyyit.telemetry.export;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class InMemoryAuditLog implements AuditLog {
    private final List<Entry> entries = new CopyOnWriteArrayList<>();

    @Override
    public void record(Entry entry) {
        entries.add(entry);
    }

    @Override
    public List<Entry> entries() {
        return List.copyOf(entries);
    }
}
