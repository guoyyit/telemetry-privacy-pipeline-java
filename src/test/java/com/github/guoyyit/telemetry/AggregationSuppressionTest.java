package com.github.guoyyit.telemetry;

import com.github.guoyyit.telemetry.domain.TelemetryEvent;
import com.github.guoyyit.telemetry.export.AggregationExporter.GroupCount;
import com.github.guoyyit.telemetry.export.CallerContext;
import com.github.guoyyit.telemetry.export.DefaultAggregationExporter;
import com.github.guoyyit.telemetry.export.InMemoryAuditLog;
import com.github.guoyyit.telemetry.store.VersionedEventStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Groups below the minimum size must be deterministically suppressed or merged. */
class AggregationSuppressionTest {
    private final CallerContext caller = new CallerContext("analyst", CallerContext.Role.STANDARD);

    private static TelemetryEvent ev(String id, String country) {
        return new TelemetryEvent(id, "u-" + id, "page_view",
                Instant.parse("2026-01-01T00:00:00Z"), Map.of("country", country));
    }

    @Test
    void smallGroupsAreSuppressed() {
        VersionedEventStore store = new VersionedEventStore();
        List<TelemetryEvent> events = new ArrayList<>();
        for (int i = 0; i < 5; i++) events.add(ev("a" + i, "CN"));
        for (int i = 0; i < 3; i++) events.add(ev("b" + i, "US"));
        events.add(ev("c0", "IS")); // 1 sample: below k=3, and alone cannot form a bucket
        store.commit("b1", events);

        List<GroupCount> out = new DefaultAggregationExporter(store, new InMemoryAuditLog())
                .countByField(caller, "country", 3);
        assertEquals(2, out.size());
        assertEquals(new GroupCount(Map.of("country", "CN"), 5, false), out.get(0));
        assertEquals(new GroupCount(Map.of("country", "US"), 3, false), out.get(1));
    }

    @Test
    void suppressedGroupsMergeIntoOtherBucketWhenLargeEnough() {
        VersionedEventStore store = new VersionedEventStore();
        store.commit("b1", List.of(ev("e1", "IS"), ev("e2", "LI"), ev("e3", "MC")));

        List<GroupCount> out = new DefaultAggregationExporter(store, new InMemoryAuditLog())
                .countByField(caller, "country", 2);
        assertEquals(1, out.size());
        assertEquals(new GroupCount(Map.of("country", "__other__"), 3, true), out.get(0));
    }

    @Test
    void aggregationIsDeterministicRegardlessOfIngestOrder() {
        List<TelemetryEvent> events = new ArrayList<>();
        for (int i = 0; i < 4; i++) events.add(ev("a" + i, "CN"));
        for (int i = 0; i < 2; i++) events.add(ev("b" + i, "US"));
        events.add(ev("c0", "IS"));

        VersionedEventStore s1 = new VersionedEventStore();
        s1.commit("b1", events);
        List<TelemetryEvent> shuffled = new ArrayList<>(events);
        Collections.reverse(shuffled);
        VersionedEventStore s2 = new VersionedEventStore();
        s2.commit("b1", shuffled);

        DefaultAggregationExporter agg1 = new DefaultAggregationExporter(s1, new InMemoryAuditLog());
        DefaultAggregationExporter agg2 = new DefaultAggregationExporter(s2, new InMemoryAuditLog());
        assertEquals(agg1.countByField(caller, "country", 2), agg2.countByField(caller, "country", 2));
    }

    @Test
    void resultNeverContainsGroupBelowMinimum() {
        VersionedEventStore store = new VersionedEventStore();
        store.commit("b1", List.of(ev("e1", "IS")));
        List<GroupCount> out = new DefaultAggregationExporter(store, new InMemoryAuditLog())
                .countByField(caller, "country", 5);
        assertTrue(out.isEmpty());
    }
}
