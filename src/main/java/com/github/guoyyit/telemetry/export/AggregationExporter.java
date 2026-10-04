package com.github.guoyyit.telemetry.export;

import java.util.List;
import java.util.Map;

/**
 * Aggregate export with k-anonymity style suppression: groups smaller than the
 * configured minimum size are deterministically suppressed or merged.
 */
public interface AggregationExporter {
    /**
     * Count events grouped by the given field. Groups with fewer than
     * {@code minGroupSize} members are suppressed (never returned).
     */
    List<GroupCount> countByField(CallerContext caller, String field, int minGroupSize);

    record GroupCount(Map<String, String> groupKey, long count, boolean suppressed) {
    }
}
