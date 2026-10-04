package com.github.guoyyit.telemetryprivacypipelinejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.guoyyit.telemetryprivacypipelinejava.export.Caller;
import com.github.guoyyit.telemetryprivacypipelinejava.model.StoredEvent;
import com.github.guoyyit.telemetryprivacypipelinejava.model.TelemetryEvent;
import com.github.guoyyit.telemetryprivacypipelinejava.tests.TestSupport.PipelineProps;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 场景 6：采集与导出并发。慢导出只持有不可变快照、不阻塞写入；
 * 导出读到的永远是某个已提交的完整版本，事件序号自洽，不出现字段级混合。
 */
class ConcurrentSnapshotTest {

    @TempDir
    Path tempDir;

    @Test
    void slowReaderSeesConsistentVersionsAndNeverBlocksWriters() throws Exception {
        PipelineProps p = TestSupport.bundle(tempDir);
        Caller analyst = new Caller("reader", Caller.ROLE_ANALYST);
        int writers = 4;
        int batches = 40;

        ExecutorService writerPool = Executors.newFixedThreadPool(writers);
        ExecutorService readerPool = Executors.newSingleThreadExecutor();
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean stopReader = new AtomicBoolean(false);
        AtomicInteger readerRounds = new AtomicInteger();
        AtomicInteger writerErrors = new AtomicInteger();

        // 慢导出线程：每轮持有快照并缓慢遍历，期间不持写锁。
        readerPool.submit(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            while (!stopReader.get()) {
                List<StoredEvent> view = p.export().queryEvents(analyst, null, false);
                // 校验：快照是某个已提交的完整版本——序号自 1 连续、无字段级混合。
                long expectedSeq = 0;
                for (StoredEvent event : view) {
                    expectedSeq++;
                    assertEquals(expectedSeq, event.seq(),
                            "快照序号不连续，疑似读到混合版本");
                    assertTrue(event.redactedFields().containsKey("content"),
                            "字段级混合：缺少完整字段 content");
                    assertTrue(event.redactedFields().containsKey("email"),
                            "字段级混合：缺少完整字段 email");
                }
                readerRounds.incrementAndGet();
                try {
                    Thread.sleep(2); // 模拟慢导出
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        // 多写入线程：每个批次独立提交，产生新版本。
        for (int w = 0; w < writers; w++) {
            final int wid = w;
            writerPool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (int b = 0; b < batches; b++) {
                    TelemetryEvent e = new TelemetryEvent(
                            "w" + wid + "-" + b, "click", "user-" + wid, true,
                            Map.of("content", "v" + b, "email", "u" + wid + "@x.com"));
                    p.ingest().ingestBatch(List.of(e), "batch-w" + wid + "-" + b);
                }
            });
        }

        start.countDown();
        writerPool.shutdown();
        // 慢导出在后台持续进行，写入必须在有界时间内全部完成（证明不被慢导出阻塞）。
        assertTrue(writerPool.awaitTermination(30, TimeUnit.SECONDS), "写入被慢导出阻塞");
        stopReader.set(true);
        readerPool.shutdown();
        assertTrue(readerPool.awaitTermination(10, TimeUnit.SECONDS));

        // 最终所有批次必须完整可见（不丢不重）
        List<StoredEvent> finalView = p.export().queryEvents(analyst, null, false);
        assertEquals(writers * batches, finalView.size());
        assertEquals(0, writerErrors.get());
        assertTrue(readerRounds.get() > 0, "慢导出线程应至少完成多轮读取");
    }
}
