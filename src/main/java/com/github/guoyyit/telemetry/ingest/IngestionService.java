package com.github.guoyyit.telemetry.ingest;

import com.github.guoyyit.telemetry.domain.IngestionResult;
import com.github.guoyyit.telemetry.domain.TelemetryEvent;

import java.util.List;

/** Entry point for client telemetry. Applies consent + collection policy before storage. */
public interface IngestionService {
    IngestionResult ingest(TelemetryEvent event);

    List<IngestionResult> ingestBatch(List<TelemetryEvent> events);
}
