package com.github.guoyyit.telemetryprivacypipelinejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.github.guoyyit.telemetryprivacypipelinejava.model.DiscardReason;
import com.github.guoyyit.telemetryprivacypipelinejava.model.IngestionResult;
import com.github.guoyyit.telemetryprivacypipelinejava.model.TelemetryEvent;
import com.github.guoyyit.telemetryprivacypipelinejava.tests.TestSupport.PipelineProps;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 场景 1：拒绝采集。未同意、类型不允许、命中拒绝规则的数据不得进入存储，
 * 丢弃原因可区分，且在审计日志中可追溯。
 */
class RejectionTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsWithDistinctReasonsAndAuditsTrail() {
        PipelineProps p = TestSupport.bundle(tempDir);

        TelemetryEvent noConsent = new TelemetryEvent("e1", "page_view", "u1",
                false, Map.of("content", "hello"));
        TelemetryEvent badType = new TelemetryEvent("e2", "location_track", "u1",
                true, Map.of("content", "hello"));
        TelemetryEvent deniedField = new TelemetryEvent("e3", "page_view", "u1",
                true, Map.of("health_data", "sensitive!"));
        TelemetryEvent valid = new TelemetryEvent("e4", "page_view", "u1",
                true, Map.of("content", "hello"));

        List<IngestionResult> results = p.ingest().ingestBatch(
                List.of(noConsent, badType, deniedField, valid), "client-batch-1");

        assertFalse(results.get(0).accepted());
        assertEquals(DiscardReason.NO_CONSENT, results.get(0).reason());
        assertFalse(results.get(1).accepted());
        assertEquals(DiscardReason.EVENT_TYPE_NOT_ALLOWED, results.get(1).reason());
        assertFalse(results.get(2).accepted());
        assertEquals(DiscardReason.DENY_RULE, results.get(2).reason());
        // 被拒绝的三条不得进入存储，只有合法事件进入。
        assertEquals(1, p.store().snapshot().events().size());
        assertEquals("e4", p.store().snapshot().events().get(0).eventId());

        // 三类拒绝原因均可在审计中区分追溯。
        long denies = p.audit().entries().stream()
                .filter(e -> "EVENT_DISCARD".equals(e.action()))
                .peek(e -> {
                    assertNotNull(e.detail());
                    assertEquals("DENY", e.decision());
                })
                .count();
        assertEquals(3, denies);
    }
}
