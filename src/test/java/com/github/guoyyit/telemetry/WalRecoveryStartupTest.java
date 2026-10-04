package com.github.guoyyit.telemetry;

import com.github.guoyyit.telemetry.domain.TelemetryEvent;
import com.github.guoyyit.telemetry.store.FileWal;
import com.github.guoyyit.telemetry.store.VersionedEventStore;
import com.github.guoyyit.telemetry.store.WalRecovery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** End-to-end: data committed before a restart is recovered by the startup runner. */
class WalRecoveryStartupTest {
    @TempDir
    Path walDir;

    private static TelemetryEvent ev(String id) {
        return new TelemetryEvent(id, "u1", "page_view",
                Instant.parse("2026-01-01T00:00:00Z"), Map.of("url", "/x"));
    }

    @Test
    void startupRunnerRestoresCommittedData() throws Exception {
        // Before restart: commit one batch, interrupt another.
        FileWal wal = new FileWal(walDir);
        VersionedEventStore oldStore = new VersionedEventStore();
        wal.beginBatch("b1", List.of(ev("e1")));
        wal.markCommitted("b1", oldStore.commit("b1", List.of(ev("e1"))));
        wal.beginBatch("b2", List.of(ev("e2"))); // never committed

        // After restart: fresh store, recovery runner replays the WAL.
        VersionedEventStore newStore = new VersionedEventStore();
        new WalRecovery(new FileWal(walDir), newStore).run(new DefaultApplicationArguments());

        assertEquals(1, newStore.snapshot().events().size());
        assertEquals("e1", newStore.snapshot().events().get(0).eventId());
        assertEquals(1, newStore.currentVersion());
    }
}
