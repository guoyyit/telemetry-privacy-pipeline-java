package com.github.guoyyit.telemetry;

import com.github.guoyyit.telemetry.domain.TelemetryEvent;
import com.github.guoyyit.telemetry.store.FileWal;
import com.github.guoyyit.telemetry.store.VersionedEventStore;
import com.github.guoyyit.telemetry.store.Wal.CommittedBatch;
import com.github.guoyyit.telemetry.store.Wal.ReplayResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Committed data survives restart exactly once; incomplete batches are discarded. */
class CrashRecoveryTest {
    @TempDir
    Path walDir;

    private static TelemetryEvent ev(String id) {
        return new TelemetryEvent(id, "u1", "page_view",
                Instant.parse("2026-01-01T00:00:00Z"), Map.of("url", "/x"));
    }

    @Test
    void committedBatchesSurviveRestartPendingAreDiscarded() {
        // Phase 1: before "crash" — one committed batch, one interrupted batch.
        FileWal wal = new FileWal(walDir);
        VersionedEventStore store = new VersionedEventStore();
        wal.beginBatch("b1", List.of(ev("e1")));
        long v1 = store.commit("b1", List.of(ev("e1")));
        wal.markCommitted("b1", v1);
        wal.beginBatch("b2", List.of(ev("e2"))); // crash before commit

        // Phase 2: after "restart" — replay into a fresh store.
        ReplayResult replay = new FileWal(walDir).replay();
        assertEquals(List.of("b2"), replay.discardedBatchIds());
        assertEquals(1, replay.committed().size());
        CommittedBatch b1 = replay.committed().get(0);
        assertEquals("b1", b1.batchId());

        VersionedEventStore recovered = new VersionedEventStore();
        for (CommittedBatch batch : replay.committed()) {
            recovered.commit(batch.batchId(), batch.events());
        }
        assertEquals(1, recovered.snapshot().events().size());
        assertEquals("e1", recovered.snapshot().events().get(0).eventId());
    }

    @Test
    void replayIsIdempotentNoDuplicates() {
        FileWal wal = new FileWal(walDir);
        wal.beginBatch("b1", List.of(ev("e1"), ev("e2")));
        wal.markCommitted("b1", 1L);

        ReplayResult replay = new FileWal(walDir).replay();
        VersionedEventStore recovered = new VersionedEventStore();
        for (CommittedBatch batch : replay.committed()) {
            recovered.commit(batch.batchId(), batch.events());
        }
        // Replaying the same WAL again (e.g. crash during recovery) must not duplicate.
        for (CommittedBatch batch : replay.committed()) {
            recovered.commit(batch.batchId(), batch.events());
        }
        assertEquals(2, recovered.snapshot().events().size());
    }

    @Test
    void batchStateIsDistinguishableOnDisk() throws Exception {
        FileWal wal = new FileWal(walDir);
        wal.beginBatch("pending-batch", List.of(ev("e1")));
        wal.beginBatch("done-batch", List.of(ev("e2")));
        wal.markCommitted("done-batch", 1L);

        try (var files = java.nio.file.Files.list(walDir)) {
            List<String> names = files.map(p -> p.getFileName().toString()).sorted().toList();
            assertEquals(List.of("done-batch.committed", "pending-batch.pending"), names);
        }
    }
}
