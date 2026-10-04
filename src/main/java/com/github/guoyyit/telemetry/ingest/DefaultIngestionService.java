package com.github.guoyyit.telemetry.ingest;

import com.github.guoyyit.telemetry.consent.ConsentRegistry;
import com.github.guoyyit.telemetry.domain.DropReason;
import com.github.guoyyit.telemetry.domain.IngestionResult;
import com.github.guoyyit.telemetry.domain.TelemetryEvent;
import com.github.guoyyit.telemetry.policy.CollectionPolicy;
import com.github.guoyyit.telemetry.store.EventStore;
import com.github.guoyyit.telemetry.store.Wal;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ingestion pipeline: consent check → collection policy → WAL → atomic commit.
 * Events failing any check never reach storage, and each drop carries a
 * distinguishable {@link DropReason}. Every accepted batch is written to the WAL
 * before commit and marked committed after, so a crash mid-batch leaves a
 * distinguishable PENDING batch that recovery discards — never a partial write.
 */
@Service
public class DefaultIngestionService implements IngestionService {
    private final ConsentRegistry consentRegistry;
    private final CollectionPolicy policy;
    private final EventStore store;
    private final Wal wal;

    public DefaultIngestionService(ConsentRegistry consentRegistry, CollectionPolicy policy,
                                   EventStore store, Wal wal) {
        this.consentRegistry = consentRegistry;
        this.policy = policy;
        this.store = store;
        this.wal = wal;
    }

    @Override
    public IngestionResult ingest(TelemetryEvent event) {
        DropReason refusal = refuse(event);
        if (refusal != null) {
            return IngestionResult.dropped(refusal);
        }
        String batchId = UUID.randomUUID().toString();
        wal.beginBatch(batchId, List.of(event));
        long version = store.commit(batchId, List.of(event));
        wal.markCommitted(batchId, version);
        return IngestionResult.accepted(batchId);
    }

    @Override
    public List<IngestionResult> ingestBatch(List<TelemetryEvent> events) {
        List<IngestionResult> results = new ArrayList<>(events.size());
        List<TelemetryEvent> accepted = new ArrayList<>();
        for (TelemetryEvent event : events) {
            DropReason refusal = refuse(event);
            if (refusal != null) {
                results.add(IngestionResult.dropped(refusal));
            } else {
                accepted.add(event);
                results.add(null); // placeholder, filled after commit
            }
        }
        if (!accepted.isEmpty()) {
            String batchId = UUID.randomUUID().toString();
            wal.beginBatch(batchId, accepted);
            long version = store.commit(batchId, accepted);
            wal.markCommitted(batchId, version);
            for (int i = 0; i < results.size(); i++) {
                if (results.get(i) == null) {
                    results.set(i, IngestionResult.accepted(batchId));
                }
            }
        }
        return results;
    }

    /** Returns the reason to refuse this event, or null if it may be collected. */
    private DropReason refuse(TelemetryEvent event) {
        if (consentRegistry.withdrawnUsers().contains(event.userId())) {
            return DropReason.CONSENT_WITHDRAWN;
        }
        if (!consentRegistry.hasConsent(event.userId())) {
            return DropReason.NO_CONSENT;
        }
        if (!policy.isEventTypeAllowed(event.eventType())) {
            return DropReason.EVENT_TYPE_DENIED;
        }
        for (String field : event.fields().keySet()) {
            if (!policy.isFieldAllowed(field)) {
                return DropReason.FIELD_POLICY_DENIED;
            }
        }
        return null;
    }
}
