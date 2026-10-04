package com.github.guoyyit.telemetry.api;

import com.github.guoyyit.telemetry.domain.TelemetryEvent;
import com.github.guoyyit.telemetry.export.AggregationExporter;
import com.github.guoyyit.telemetry.export.CallerContext;
import com.github.guoyyit.telemetry.export.ExportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/export")
public class ExportController {
    private final ExportService exportService;
    private final AggregationExporter aggregationExporter;

    public ExportController(ExportService exportService, AggregationExporter aggregationExporter) {
        this.exportService = exportService;
        this.aggregationExporter = aggregationExporter;
    }

    @GetMapping
    public List<TelemetryEvent> export(@RequestHeader("X-Caller-Id") String callerId,
                                       @RequestHeader(value = "X-Caller-Role", defaultValue = "STANDARD") String role,
                                       @RequestParam(defaultValue = "false") boolean raw) {
        return exportService.export(new CallerContext(callerId, CallerContext.Role.valueOf(role)), raw);
    }

    @GetMapping("/aggregate")
    public List<AggregationExporter.GroupCount> aggregate(@RequestHeader("X-Caller-Id") String callerId,
                                                          @RequestParam String field,
                                                          @RequestParam(defaultValue = "5") int minGroupSize) {
        return aggregationExporter.countByField(new CallerContext(callerId, CallerContext.Role.STANDARD),
                field, minGroupSize);
    }
}
