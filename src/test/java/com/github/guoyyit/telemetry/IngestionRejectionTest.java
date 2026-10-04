package com.github.guoyyit.telemetry;

import com.github.guoyyit.telemetry.consent.InMemoryConsentRegistry;
import com.github.guoyyit.telemetry.domain.DropReason;
import com.github.guoyyit.telemetry.domain.IngestionResult;
import com.github.guoyyit.telemetry.domain.TelemetryEvent;
import com.github.guoyyit.telemetry.ingest.DefaultIngestionService;
import com.github.guoyyit.telemetry.policy.CollectionPolicy;
import com.github.guoyyit.telemetry.store.FileWal;
import com.github.guoyyit.telemetry.store.VersionedEventStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Dropped data must never reach storage, and drop reasons must be distinguishable. */
class IngestionRejectionTest {
    @TempDir
    Path walDir;

    InMemoryConsentRegistry consent;
    VersionedEventStore store;
    DefaultIngestionService ingestion;

    @BeforeEach
    void setUp() {
        consent = new InMemoryConsentRegistry();
        store = new VersionedEventStore();
        CollectionPolicy policy = new CollectionPolicy(Set.of("page_view"), Set.of("ssn"));
        ingestion = new DefaultIngestionService(consent, policy, store, new FileWal(walDir));
    }

    private TelemetryEvent event(String user, String type, Map<String, String> fields) {
        return new TelemetryEvent("e-" + user + type, user, type, Instant.now(), fields);
    }

    @Test
    void noConsentIsDroppedWithReason() {
        IngestionResult r = ingestion.ingest(event("u1", "page_view", Map.of("url", "/a")));
        assertFalse(r.accepted());
        assertEquals(DropReason.NO_CONSENT, r.dropReason());
        assertTrue(store.snapshot().events().isEmpty());
    }

    @Test
    void withdrawnConsentIsDistinguishableFromNeverConsented() {
        consent.grant("u2");
        consent.withdraw("u2");
        IngestionResult r = ingestion.ingest(event("u2", "page_view", Map.of("url", "/a")));
        assertFalse(r.accepted());
        assertEquals(DropReason.CONSENT_WITHDRAWN, r.dropReason());
        assertTrue(store.snapshot().events().isEmpty());
    }

    @Test
    void deniedEventTypeIsDropped() {
        consent.grant("u3");
        IngestionResult r = ingestion.ingest(event("u3", "keystroke", Map.of("k", "x")));
        assertFalse(r.accepted());
        assertEquals(DropReason.EVENT_TYPE_DENIED, r.dropReason());
        assertTrue(store.snapshot().events().isEmpty());
    }

    @Test
    void deniedFieldIsDropped() {
        consent.grant("u4");
        IngestionResult r = ingestion.ingest(event("u4", "page_view", Map.of("ssn", "123-45-6789")));
        assertFalse(r.accepted());
        assertEquals(DropReason.FIELD_POLICY_DENIED, r.dropReason());
        assertTrue(store.snapshot().events().isEmpty());
    }

    @Test
    void allowedEventIsAcceptedAndStored() {
        consent.grant("u5");
        IngestionResult r = ingestion.ingest(event("u5", "page_view", Map.of("url", "/home")));
        assertTrue(r.accepted());
        assertEquals(1, store.snapshot().events().size());
    }
}
