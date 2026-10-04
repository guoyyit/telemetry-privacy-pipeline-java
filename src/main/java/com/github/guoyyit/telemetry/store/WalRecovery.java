package com.github.guoyyit.telemetry.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Startup recovery: replays WAL-committed batches into the store in version order and
 * discards incomplete (PENDING) batches. Committed data is neither lost nor duplicated
 * (store commits are idempotent per batch id), and partial writes never appear.
 */
@Component
public class WalRecovery implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(WalRecovery.class);

    private final Wal wal;
    private final EventStore store;

    public WalRecovery(Wal wal, EventStore store) {
        this.wal = wal;
        this.store = store;
    }

    @Override
    public void run(ApplicationArguments args) {
        Wal.ReplayResult replay = wal.replay();
        for (Wal.CommittedBatch batch : replay.committed()) {
            store.commit(batch.batchId(), batch.events());
        }
        if (!replay.committed().isEmpty() || !replay.discardedBatchIds().isEmpty()) {
            log.info("WAL recovery: replayed {} committed batches, discarded {} incomplete batches {}",
                    replay.committed().size(), replay.discardedBatchIds().size(),
                    replay.discardedBatchIds());
        }
    }
}
