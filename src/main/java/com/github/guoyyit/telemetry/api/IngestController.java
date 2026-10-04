package com.github.guoyyit.telemetry.api;

import com.github.guoyyit.telemetry.domain.IngestionResult;
import com.github.guoyyit.telemetry.domain.TelemetryEvent;
import com.github.guoyyit.telemetry.ingest.IngestionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ingest")
public class IngestController {
    private final IngestionService ingestionService;

    public IngestController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping
    public IngestionResult ingest(@RequestBody TelemetryEvent event) {
        return ingestionService.ingest(event);
    }

    @PostMapping("/batch")
    public List<IngestionResult> ingestBatch(@RequestBody List<TelemetryEvent> events) {
        return ingestionService.ingestBatch(events);
    }
}
