package com.github.guoyyit.telemetry.store;

import com.github.guoyyit.telemetry.domain.TelemetryEvent;

import java.util.List;

/**
 * Write-ahead log for crash recovery. Batches are written as PENDING then marked
 * COMMITTED; on restart, committed batches are replayed and incomplete batches are
 * discarded, so no partial writes survive and no committed data is lost or duplicated.
 */
public interface Wal {
    void beginBatch(String batchId, List<TelemetryEvent> events);

    void markCommitted(String batchId, long version);

    /** Replay committed batches in order; incomplete batches are reported as discarded. */
    ReplayResult replay();

    record ReplayResult(List<CommittedBatch> committed, List<String> discardedBatchIds) {
    }

    record CommittedBatch(String batchId, long version, List<TelemetryEvent> events) {
    }
}
