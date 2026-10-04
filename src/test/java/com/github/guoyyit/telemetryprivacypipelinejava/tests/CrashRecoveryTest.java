package com.github.guoyyit.telemetryprivacypipelinejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.guoyyit.telemetryprivacypipelinejava.audit.FileAuditLog;
import com.github.guoyyit.telemetryprivacypipelinejava.config.PipelineProperties;
import com.github.guoyyit.telemetryprivacypipelinejava.model.IngestionResult;
import com.github.guoyyit.telemetryprivacypipelinejava.model.RecoveryReport;
import com.github.guoyyit.telemetryprivacypipelinejava.model.StoredEvent;
import com.github.guoyyit.telemetryprivacypipelinejava.model.TelemetryEvent;
import com.github.guoyyit.telemetryprivacypipelinejava.store.DurableEventLog;
import com.github.guoyyit.telemetryprivacypipelinejava.store.EventStoreImpl;
import com.github.guoyyit.telemetryprivacypipelinejava.tests.TestSupport.PipelineProps;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 场景 7：写入/导出中断后的恢复。
 * 重启后已提交批次不丢不重；只有 PREPARE 没有 COMMIT 的批次被判定为未完成并回滚，
 * 状态可区分、不留部分写入。
 */
class CrashRecoveryTest {

    @TempDir
    Path tempDir;

    private EventStoreImpl rehydrate(PipelineProperties props) {
        // 用同一数据目录构造全新组件，模拟进程重启。
        DurableEventLog log = new DurableEventLog(props);
        EventStoreImpl store = new EventStoreImpl(log);
        store.recover();
        return store;
    }

    @Test
    void committedBatchesSurviveRestartWithoutLossOrDuplication() {
        PipelineProps p = TestSupport.bundle(tempDir);
        for (int i = 0; i < 5; i++) {
            p.ingest().ingest(new TelemetryEvent("e" + i, "page_view", "u" + i,
                    true, Map.of("content", "v" + i)));
        }
        assertEquals(5, p.store().snapshot().events().size());

        // 模拟重启
        EventStoreImpl restored = rehydrate(p.properties());
        List<StoredEvent> events = restored.snapshot().events();
        assertEquals(5, events.size());
        // 序号全局唯一且连续 => 不丢不重
        for (int i = 0; i < 5; i++) {
            assertEquals(i + 1L, events.get(i).seq());
            assertEquals("e" + i, events.get(i).eventId());
        }

        // 再重启一次结果仍一致（幂等恢复）
        EventStoreImpl restoredAgain = rehydrate(p.properties());
        assertEquals(events, restoredAgain.snapshot().events());
    }

    @Test
    void preparedButUncommittedBatchIsRolledBackAndDistinguishable() throws Exception {
        PipelineProps p = TestSupport.bundle(tempDir);
        long now = System.currentTimeMillis();

        // 批次 A 完整提交
        p.ingest().ingestBatch(List.of(
                new TelemetryEvent("a1", "page_view", "u1", true, Map.of("content", "a"))),
                "batch-A");

        // 批次 B 模拟崩溃：只写了 PREPARE + 一条事件，没有 COMMIT。
        // 直接操作持久化日志，构造“写到一半进程被杀死”的现场。
        StoredEvent partial = new StoredEvent(0, "b1", "page_view", "u2", now, "batch-B",
                Map.of("content", "b"), Map.of("content", "b"));
        p.log().prepareBatch("batch-B", now);
        p.log().writeEvent(partial);
        // 进程在此处崩溃：没有 commitBatch("batch-B")

        // 用全新组件恢复
        EventStoreImpl restored = rehydrate(p.properties());
        List<StoredEvent> events = restored.snapshot().events();
        assertEquals(1, events.size());
        assertEquals("a1", events.get(0).eventId());

        RecoveryReport report = new RecoveryReport(0L, 0L, 0L, List.of(), List.of());
        // 重新执行一次 recover 以取回报告（recover 幂等，第二次只产生相同内存状态）
        DurableEventLog replayLog = new DurableEventLog(p.properties());
        EventStoreImpl store2 = new EventStoreImpl(replayLog);
        RecoveryReport r = store2.recover();
        assertEquals(List.of("batch-B"), r.rolledBackBatches());
        assertTrue(r.recoveredBatches().contains("batch-A"));
        assertFalse(r.recoveredBatches().contains("batch-B"));

        // 回滚批次写入 rollback.log，可追溯
        Path rollback = tempDir.resolve(DurableEventLog.ROLLBACK_FILE);
        assertTrue(Files.exists(rollback));
        String content = Files.readString(rollback, StandardCharsets.UTF_8);
        assertTrue(content.contains("batch-B"));
        assertFalse(content.contains("batch-A"));

        // 未完成批次不留部分写入：部分事件在任何恢复后都不可见，且其 eventId 可重新使用
        List<IngestionResult> retry = new com.github.guoyyit.telemetryprivacypipelinejava
                .ingest.IngestionServiceImpl(
                store2, new com.github.guoyyit.telemetryprivacypipelinejava.policy.PolicyFactory(),
                new com.github.guoyyit.telemetryprivacypipelinejava.policy.FieldSanitizer(),
                new FileAuditLog(p.properties()), p.properties())
                .ingestBatch(List.of(
                        new TelemetryEvent("b1", "page_view", "u2", true,
                                Map.of("content", "b-retry"))), "batch-B-retry");
        assertTrue(retry.get(0).accepted());
        assertEquals(2, store2.snapshot().events().size());
    }

    @Test
    void deletionsAndPurgeAlsoSurviveRestart() {
        PipelineProps p = TestSupport.bundle(tempDir);
        p.ingest().ingest(new TelemetryEvent("a", "page_view", "u1", true,
                Map.of("content", "x")));
        p.ingest().ingest(new TelemetryEvent("b", "page_view", "u2", true,
                Map.of("content", "y")));
        p.retention().deleteUsers(java.util.Set.of("u1"), "CONSENT_WITHDRAWN");

        EventStoreImpl restored = rehydrate(p.properties());
        assertEquals(1, restored.snapshot().events().size());
        assertTrue(restored.snapshot().deletedUsers().contains("u1"));
        assertTrue(restored.isDeleted("u1"));
    }
}
