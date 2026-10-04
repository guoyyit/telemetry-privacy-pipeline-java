package com.github.guoyyit.telemetryprivacypipelinejava.ingest;

import java.util.List;
import com.github.guoyyit.telemetryprivacypipelinejava.model.IngestionResult;
import com.github.guoyyit.telemetryprivacypipelinejava.model.TelemetryEvent;

/**
 * 采集入口：策略判定 -> 脱敏 -> 原子批次提交；拒绝原因可区分且全部留痕。
 */
public interface IngestionService {

    IngestionResult ingest(TelemetryEvent event);

    List<IngestionResult> ingestBatch(List<TelemetryEvent> events, String clientBatchId);
}
