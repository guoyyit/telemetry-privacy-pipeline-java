package com.github.guoyyit.telemetry.store;

import com.github.guoyyit.telemetry.domain.TelemetryEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * MVCC-style store. The committed state is an immutable object swapped atomically;
 * readers take a snapshot without locking, so slow exports never block writers and
 * always observe one complete committed version (no field-level mixing).
 *
 * <p>Deletions install persistent tombstones (deleted user ids, retention cutoff).
 * Concurrent commits that were prepared before a deletion are filtered against the
 * tombstones at commit time, so deleted data cannot be resurrected by in-flight writes.
 *
 * <p>Commits are idempotent per batch id: re-committing an already committed batch is
 * a no-op, which together with the WAL guarantees no duplicates after recovery.
 */
@Component
public class VersionedEventStore implements EventStore {

    /** Immutable committed state. */
    private record State(long version, List<TelemetryEvent> events,
                         Set<String> deletedUsers, Instant purgeCutoff,
                         Set<String> committedBatchIds) {
        static State empty() {
            return new State(0, List.of(), Set.of(), Instant.EPOCH, Set.of());
        }
    }

    private final ReentrantLock lock = new ReentrantLock();
    private volatile State state = State.empty();

    @Override
    public long commit(String batchId, List<TelemetryEvent> events) {
        lock.lock();
        try {
            State cur = state;
            if (cur.committedBatchIds().contains(batchId)) {
                return cur.version(); // idempotent: already committed
            }
            List<TelemetryEvent> nextEvents = new ArrayList<>(cur.events().size() + events.size());
            nextEvents.addAll(cur.events());
            for (TelemetryEvent e : events) {
                // Tombstone filter: never let deleted/purged data back in.
                if (cur.deletedUsers().contains(e.userId())) {
                    continue;
                }
                if (e.timestamp() != null && e.timestamp().isBefore(cur.purgeCutoff())) {
                    continue;
                }
                nextEvents.add(e);
            }
            Set<String> nextBatchIds = new HashSet<>(cur.committedBatchIds());
            nextBatchIds.add(batchId);
            state = new State(cur.version() + 1, List.copyOf(nextEvents), cur.deletedUsers(),
                    cur.purgeCutoff(), Set.copyOf(nextBatchIds));
            return state.version();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Snapshot snapshot() {
        State s = state; // single volatile read: one complete committed version
        return new Snapshot(s.version(), s.events());
    }

    @Override
    public int deleteUser(String userId) {
        lock.lock();
        try {
            State cur = state;
            List<TelemetryEvent> kept = cur.events().stream()
                    .filter(e -> !userId.equals(e.userId()))
                    .toList();
            int removed = cur.events().size() - kept.size();
            Set<String> deleted = new HashSet<>(cur.deletedUsers());
            deleted.add(userId);
            state = new State(cur.version() + 1, kept, Set.copyOf(deleted), cur.purgeCutoff(),
                    cur.committedBatchIds());
            return removed;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int purgeBefore(Instant cutoff) {
        lock.lock();
        try {
            State cur = state;
            Instant effective = cutoff.isAfter(cur.purgeCutoff()) ? cutoff : cur.purgeCutoff();
            List<TelemetryEvent> kept = cur.events().stream()
                    .filter(e -> e.timestamp() == null || !e.timestamp().isBefore(effective))
                    .toList();
            int removed = cur.events().size() - kept.size();
            state = new State(cur.version() + 1, kept, cur.deletedUsers(), effective,
                    cur.committedBatchIds());
            return removed;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long currentVersion() {
        return state.version();
    }
}
