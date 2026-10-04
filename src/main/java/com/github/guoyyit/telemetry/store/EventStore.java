package com.github.guoyyit.telemetry.store;

import com.github.guoyyit.telemetry.domain.TelemetryEvent;

import java.time.Instant;
import java.util.List;

/**
 * Versioned, MVCC-style store. Each commit produces a new immutable version; readers
 * always observe one complete committed version (never a field-level mix), and slow
 * readers never block writers.
 */
public interface EventStore {
    /** Atomically commit a batch of events as a new version. Returns the new version. */
    long commit(String batchId, List<TelemetryEvent> events);

    /** A consistent, immutable view of one committed version. */
    Snapshot snapshot();

    /** Erase all events of a user (consent withdrawal / deletion request). */
    int deleteUser(String userId);

    /** Erase all events older than the given instant (retention). */
    int purgeBefore(Instant cutoff);

    long currentVersion();

    /** Immutable view of the store at one version. Safe to read while writers commit. */
    record Snapshot(long version, List<TelemetryEvent> events) {
    }
}
