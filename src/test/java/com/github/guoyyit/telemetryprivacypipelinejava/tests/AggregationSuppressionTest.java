package com.github.guoyyit.telemetryprivacypipelinejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.guoyyit.telemetryprivacypipelinejava.export.Aggregator;
import com.github.guoyyit.telemetryprivacypipelinejava.export.Caller;
import com.github.guoyyit.telemetryprivacypipelinejava.model.AggregateRow;
import com.github.guoyyit.telemetryprivacypipelinejava.model.StoredEvent;
import com.github.guoyyit.telemetryprivacypipelinejava.model.TelemetryEvent;
import com.github.guoyyit.telemetryprivacypipelinejava.tests.TestSupport.PipelineProps;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 场景 4：聚合抑制。低于最小分组规模的取值必须被抑制或确定性合并；
 * 输出取值域规模不小于配置阈值，且行为确定（顺序稳定、重复调用一致）。
 */
class AggregationSuppressionTest {

    @TempDir
    Path tempDir;

    private StoredEvent event(String id, String groupValue) {
        // 直接构造脱敏视图事件用于聚合器单测
        return new StoredEvent(0, id, "page_view", null, 0L, "b",
                Map.of("g", groupValue), Map.of("g", groupValue));
    }

    @Test
    void suppressesSmallGroupsAndMergesDeterministically() {
        // minGroupSize=3：a 出现 3 次（保留）；b、c 各 1 次（抑制后合并为 __other__=2 <3 => 仍抑制）
        Aggregator aggregator = new Aggregator();
        List<StoredEvent> events = new ArrayList<>();
        events.add(event("1", "a"));
        events.add(event("2", "c")); // 故意乱序：c 在 b 前
        events.add(event("3", "a"));
        events.add(event("4", "b"));
        events.add(event("5", "a"));

        List<AggregateRow> rows = aggregator.aggregate(events, "g", 3, true);
        assertEquals(1, rows.size());
        assertEquals("a", rows.get(0).groupKey());
        assertEquals(3, rows.get(0).count());
        assertFalse(rows.get(0).other());
    }

    @Test
    void mergedOtherRowMustAlsoMeetThreshold() {
        // b=2 + c=2 = 4 >= minGroupSize 3 => __other__ 出现
        Aggregator aggregator = new Aggregator();
        List<StoredEvent> events = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            events.add(event("a" + i, "a"));
        }
        events.add(event("b1", "b"));
        events.add(event("b2", "b"));
        events.add(event("c1", "c"));
        events.add(event("c2", "c"));

        List<AggregateRow> rows = aggregator.aggregate(events, "g", 3, true);
        assertEquals(2, rows.size());
        assertEquals("a", rows.get(0).groupKey());
        assertEquals(Aggregator.OTHER_KEY, rows.get(1).groupKey());
        assertEquals(4, rows.get(1).count());
        assertTrue(rows.get(1).other());
    }

    @Test
    void suppressionWithoutMergeRemovesSmallGroupsEntirely() {
        Aggregator aggregator = new Aggregator();
        List<StoredEvent> events = List.of(event("1", "a"), event("2", "a"),
                event("3", "b"), event("4", "c"));
        List<AggregateRow> rows = aggregator.aggregate(events, "g", 3, false);
        assertEquals(0, rows.size());
    }

    @Test
    void outputIsDeterministicAcrossRepeatedCalls() {
        PipelineProps p = TestSupport.bundle(tempDir, 3, true);
        List<TelemetryEvent> batch = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            batch.add(new TelemetryEvent("x" + i, "page_view", "u" + i, true,
                    Map.of("content", "x")));
        }
        for (int i = 0; i < 4; i++) {
            batch.add(new TelemetryEvent("y" + i, "page_view", "v" + i, true,
                    Map.of("content", "y")));
        }
        p.ingest().ingestBatch(batch, "b1");

        Caller analyst = new Caller("a", Caller.ROLE_ANALYST);
        List<AggregateRow> first = p.export().aggregate(analyst, "content", false);
        List<AggregateRow> second = p.export().aggregate(analyst, "content", false);
        assertEquals(first, second);
        // 每行计数都不小于最小分组规模
        first.forEach(row -> assertTrue(row.count() >= 3));
    }
}
