package com.github.guoyyit.telemetry.export;

import java.time.Instant;
import java.util.List;

/** Records export/query access decisions, especially denied ones. */
public interface AuditLog {
    void record(Entry entry);

    List<Entry> entries();

    record Entry(Instant at, String callerId, String action, boolean allowed, String detail) {
    }
}
