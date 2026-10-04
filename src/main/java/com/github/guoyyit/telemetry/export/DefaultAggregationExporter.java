package com.github.guoyyit.telemetry.export;

import com.github.guoyyit.telemetry.domain.TelemetryEvent;
import com.github.guoyyit.telemetry.store.EventStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Aggregate export with deterministic k-anonymity: groups with fewer than
 * {@code minGroupSize} members are never reported individually. They are merged into
 * a single {@code __other__} bucket, which itself is emitted only if it meets the
 * minimum size; otherwise it is fully suppressed. Results are sorted by group key so
 * the output is identical for identical committed state, regardless of event order.
 */
@Service
public class DefaultAggregationExporter implements AggregationExporter {
    static final String OTHER_BUCKET = "__other__";

    private final EventStore store;
    private final AuditLog auditLog;

    public DefaultAggregationExporter(EventStore store, AuditLog auditLog) {
        this.store = store;
        this.auditLog = auditLog;
    }

    @Override
    public List<GroupCount> countByField(CallerContext caller, String field, int minGroupSize) {
        if (minGroupSize < 1) {
            throw new IllegalArgumentException("minGroupSize must be >= 1");
        }
        EventStore.Snapshot snapshot = store.snapshot();

        // TreeMap: deterministic grouping order independent of event arrival order.
        Map<String, Long> counts = new TreeMap<>();
        for (TelemetryEvent e : snapshot.events()) {
            String value = e.fields().get(field);
            if (value != null) {
                counts.merge(value, 1L, Long::sum);
            }
        }

        List<GroupCount> result = new ArrayList<>();
        long suppressedTotal = 0;
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            if (entry.getValue() >= minGroupSize) {
                result.add(new GroupCount(Map.of(field, entry.getKey()), entry.getValue(), false));
            } else {
                suppressedTotal += entry.getValue();
            }
        }
        if (suppressedTotal >= minGroupSize) {
            result.add(new GroupCount(Map.of(field, OTHER_BUCKET), suppressedTotal, true));
        }
        auditLog.record(new AuditLog.Entry(Instant.now(), caller.callerId(),
                "EXPORT_AGGREGATE", true,
                "field=" + field + " minGroupSize=" + minGroupSize + " version=" + snapshot.version()));
        return List.copyOf(result);
    }
}
