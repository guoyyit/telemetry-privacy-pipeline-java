package com.github.guoyyit.telemetryprivacypipelinejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.guoyyit.telemetryprivacypipelinejava.model.DiscardReason;
import com.github.guoyyit.telemetryprivacypipelinejava.model.IngestionResult;
import com.github.guoyyit.telemetryprivacypipelinejava.model.TelemetryEvent;
import com.github.guoyyit.telemetryprivacypipelinejava.tests.TestSupport.PipelineProps;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 场景 5：保留期与删除。撤回同意后既有数据被清除，之后并发/迟到的写入不得让其复活；
 * 保留期清除会移除过期事件。
 */
class DeletionResurrectionTest {

    @TempDir
    Path tempDir;

    @Test
    void consentWithdrawalPurgesDataAndRejectsReingestion() {
        PipelineProps p = TestSupport.bundle(tempDir);
        p.ingest().ingest(new TelemetryEvent("e1", "page_view", "u1", true,
                Map.of("content", "a")));
        p.ingest().ingest(new TelemetryEvent("e2", "page_view", "u1", true,
                Map.of("content", "b")));
        p.ingest().ingest(new TelemetryEvent("e3", "page_view", "u2", true,
                Map.of("content", "c")));
        assertEquals(3, p.store().snapshot().events().size());

        int removed = p.retention().deleteUsers(Set.of("u1"), "CONSENT_WITHDRAWN");
        assertEquals(2, removed);
        assertEquals(1, p.store().snapshot().events().size());
        assertTrue(p.store().snapshot().deletedUsers().contains("u1"));

        // 删除后再次上报同一用户：必须被拒绝（CONSENT_WITHDRAWN），数据不复活
        IngestionResult late = p.ingest().ingest(new TelemetryEvent("e4", "page_view", "u1",
                true, Map.of("content", "resurrect?")));
        assertFalse(late.accepted());
        assertEquals(DiscardReason.CONSENT_WITHDRAWN, late.reason());
        assertEquals(1, p.store().snapshot().events().size());
        assertEquals("u2", p.store().snapshot().events().get(0).userId());
    }

    @Test
    void concurrentWritesDuringDeletionDoNotResurrectDeletedUser() throws Exception {
        PipelineProps p = TestSupport.bundle(tempDir);
        // 预置 u1 数据
        p.ingest().ingest(new TelemetryEvent("seed", "page_view", "u1", true,
                Map.of("content", "x")));

        int threads = 8;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger accepted = new AtomicInteger();

        // 多个写入线程在删除发生前后持续写入 u1
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (int i = 0; i < perThread; i++) {
                    IngestionResult r = p.ingest().ingest(new TelemetryEvent(
                            "w-" + tid + "-" + i, "click", "u1", true,
                            Map.of("content", "n")));
                    if (r.accepted()) {
                        accepted.incrementAndGet();
                    } else if (r.reason() == DiscardReason.CONSENT_WITHDRAWN) {
                        rejected.incrementAndGet();
                    }
                }
            });
        }
        // 删除线程
        pool.submit(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            p.retention().deleteUsers(Set.of("u1"), "CONSENT_WITHDRAWN");
        });

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        // 关键不变量：删除完成后的任何快照里都不存在 u1 的数据（无复活）
        p.store().snapshot().events().forEach(e -> assertFalse("u1".equals(e.userId()),
                "已删除用户数据复活: " + e.eventId()));
        assertEquals(0, p.store().snapshot().events().size());
        // 删除前可能有少量写入被接受（accepted），删除后全部必须被拒绝
        assertEquals(threads * perThread, accepted.get() + rejected.get());
        assertTrue(rejected.get() > 0, "删除后应至少有并发写入被拒绝");
    }

    @Test
    void expiredEventsArePurgedByRetention() throws Exception {
        PipelineProps p = TestSupport.bundle(tempDir);
        // 保留期设为 1 天
        p.properties().setRetentionDays(1);
        long oldTs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(5);

        // 直接通过存储写入一条“老”事件：构造 StoredEvent 并以批次提交
        p.ingest().ingest(new TelemetryEvent("old", "page_view", "u9", true,
                Map.of("content", "old")));
        p.ingest().ingest(new TelemetryEvent("new", "page_view", "u9", true,
                Map.of("content", "new")));
        // 将 old 事件时间戳改老：重新构造存储状态通过 appendBatch 注入
        com.github.guoyyit.telemetryprivacypipelinejava.model.StoredEvent aged =
                new com.github.guoyyit.telemetryprivacypipelinejava.model.StoredEvent(
                        0, "aged", "click", "u9", oldTs, "aged-batch",
                        Map.of("content", "aged"), Map.of("content", "aged"));
        p.store().appendBatch("aged-batch", List.of(aged));
        assertEquals(3, p.store().snapshot().events().size());

        int purged = p.retention().purgeExpired();
        assertTrue(purged >= 1);
        p.store().snapshot().events().forEach(e ->
                assertTrue(e.timestampMs() >= System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2),
                        "存在未清除的过期事件: " + e.eventId()));
    }
}
