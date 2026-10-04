package com.github.guoyyit.telemetry;

import com.github.guoyyit.telemetry.domain.TelemetryEvent;
import com.github.guoyyit.telemetry.store.EventStore.Snapshot;
import com.github.guoyyit.telemetry.store.VersionedEventStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deleted data must not be resurrected by concurrent writes; snapshots are consistent. */
class DeletionAndConcurrencyTest {
    private static TelemetryEvent ev(String id, String user, Instant ts) {
        return new TelemetryEvent(id, user, "page_view", ts, Map.of("url", "/x"));
    }

    @Test
    void deletedUserIsNotResurrectedByInflightCommit() {
        VersionedEventStore store = new VersionedEventStore();
        Instant now = Instant.now();
        store.commit("b1", List.of(ev("e1", "u1", now)));

        // A writer prepared a batch containing u1's data before the deletion...
        List<TelemetryEvent> inflightBatch = List.of(ev("e2", "u1", now), ev("e3", "u2", now));
        // ...the deletion lands first...
        store.deleteUser("u1");
        // ...then the stale batch commits: u1's event must be filtered out.
        store.commit("b2", inflightBatch);

        Snapshot snap = store.snapshot();
        assertTrue(snap.events().stream().noneMatch(e -> e.userId().equals("u1")));
        assertTrue(snap.events().stream().anyMatch(e -> e.userId().equals("u2")));
    }

    @Test
    void purgedDataIsNotResurrectedByStaleCommit() {
        VersionedEventStore store = new VersionedEventStore();
        Instant old = Instant.parse("2025-01-01T00:00:00Z");
        Instant cutoff = Instant.parse("2026-01-01T00:00:00Z");
        store.commit("b1", List.of(ev("e1", "u1", old)));
        store.purgeBefore(cutoff);
        assertTrue(store.snapshot().events().isEmpty());

        // A stale in-flight write carrying pre-cutoff data must be filtered.
        store.commit("b2", List.of(ev("e2", "u2", old), ev("e3", "u2", cutoff.plusSeconds(1))));
        Snapshot snap = store.snapshot();
        assertEquals(1, snap.events().size());
        assertEquals("e3", snap.events().get(0).eventId());
    }

    @Test
    void oldSnapshotRemainsConsistentAfterDeletion() {
        VersionedEventStore store = new VersionedEventStore();
        store.commit("b1", List.of(ev("e1", "u1", Instant.now())));
        Snapshot before = store.snapshot();
        store.deleteUser("u1");
        // The previously taken snapshot is an immutable committed version.
        assertEquals(1, before.events().size());
        assertTrue(store.snapshot().events().isEmpty());
    }

    @Test
    void concurrentReadersNeverSeePartialBatches() throws Exception {
        VersionedEventStore store = new VersionedEventStore();
        int writers = 4, batchesPerWriter = 25, batchSize = 10;
        ExecutorService pool = Executors.newFixedThreadPool(writers + 1);
        AtomicBoolean violation = new AtomicBoolean(false);
        CountDownLatch start = new CountDownLatch(1);

        for (int w = 0; w < writers; w++) {
            int writer = w;
            pool.submit(() -> {
                await(start);
                for (int b = 0; b < batchesPerWriter; b++) {
                    List<TelemetryEvent> batch = new ArrayList<>();
                    for (int i = 0; i < batchSize; i++) {
                        batch.add(ev("w" + writer + "-b" + b + "-" + i, "u" + writer, Instant.now()));
                    }
                    store.commit("w" + writer + "-b" + b, batch);
                }
            });
        }
        pool.submit(() -> {
            await(start);
            for (int i = 0; i < 2000; i++) {
                Snapshot snap = store.snapshot();
                // Every batch is all-or-nothing: count must be a multiple of batchSize,
                // and versions must match the events actually observed.
                if (snap.events().size() % batchSize != 0) {
                    violation.set(true);
                }
            }
        });
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertFalse(violation.get(), "reader observed a partial batch");
        assertEquals(writers * batchesPerWriter * batchSize, store.snapshot().events().size());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
