package com.github.guoyyit.telemetry;

import com.github.guoyyit.telemetry.consent.InMemoryConsentRegistry;
import com.github.guoyyit.telemetry.domain.TelemetryEvent;
import com.github.guoyyit.telemetry.retention.DefaultRetentionService;
import com.github.guoyyit.telemetry.store.VersionedEventStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Expired data and withdrawn-consent data must be erased. */
class RetentionTest {
    private static TelemetryEvent ev(String id, String user, Instant ts) {
        return new TelemetryEvent(id, user, "page_view", ts, Map.of("url", "/x"));
    }

    @Test
    void expiredEventsArePurged() {
        VersionedEventStore store = new VersionedEventStore();
        Instant now = Instant.parse("2026-06-01T00:00:00Z");
        store.commit("b1", List.of(
                ev("old", "u1", now.minus(Duration.ofDays(40))),
                ev("fresh", "u1", now.minus(Duration.ofDays(1)))));
        DefaultRetentionService retention =
                new DefaultRetentionService(store, new InMemoryConsentRegistry(), Duration.ofDays(30));
        assertEquals(1, retention.enforce(now));
        assertEquals(List.of("fresh"), store.snapshot().events().stream()
                .map(TelemetryEvent::eventId).toList());
    }

    @Test
    void withdrawnUserDataIsErased() {
        VersionedEventStore store = new VersionedEventStore();
        InMemoryConsentRegistry consent = new InMemoryConsentRegistry();
        Instant now = Instant.now();
        store.commit("b1", List.of(ev("e1", "u1", now), ev("e2", "u2", now)));
        consent.withdraw("u1");
        DefaultRetentionService retention =
                new DefaultRetentionService(store, consent, Duration.ofDays(30));
        assertEquals(1, retention.enforce(now));
        assertTrue(store.snapshot().events().stream().noneMatch(e -> e.userId().equals("u1")));
        assertEquals(1, store.snapshot().events().size());
    }
}
