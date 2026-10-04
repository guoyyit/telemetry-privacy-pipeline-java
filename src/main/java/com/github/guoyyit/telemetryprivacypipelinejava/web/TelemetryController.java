package com.github.guoyyit.telemetryprivacypipelinejava.web;

import com.github.guoyyit.telemetryprivacypipelinejava.audit.AuditLog;
import com.github.guoyyit.telemetryprivacypipelinejava.export.Caller;
import com.github.guoyyit.telemetryprivacypipelinejava.export.ExportService;
import com.github.guoyyit.telemetryprivacypipelinejava.ingest.IngestionService;
import com.github.guoyyit.telemetryprivacypipelinejava.model.AggregateRow;
import com.github.guoyyit.telemetryprivacypipelinejava.model.IngestionResult;
import com.github.guoyyit.telemetryprivacypipelinejava.model.RecoveryReport;
import com.github.guoyyit.telemetryprivacypipelinejava.model.StoredEvent;
import com.github.guoyyit.telemetryprivacypipelinejava.model.TelemetryEvent;
import com.github.guoyyit.telemetryprivacypipelinejava.recovery.StartupRecovery;
import com.github.guoyyit.telemetryprivacypipelinejava.retention.RetentionService;
import com.github.guoyyit.telemetryprivacypipelinejava.web.dto.EventDtos.BatchRequest;
import com.github.guoyyit.telemetryprivacypipelinejava.web.dto.EventDtos.EventRequest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 采集 / 查询 / 聚合 / 删除 / 审计 / 恢复状态的 REST 入口。
 *
 * <p>调用方身份通过 {@code X-Caller-Id} 与 {@code X-Caller-Role} 请求头传递（生产环境应由
 * 认证网关注入）；{@code raw=true} 的原始值访问仅 PRIVACY_ADMIN 成功，其余被拒绝并留痕。</p>
 */
@RestController
@RequestMapping("/api")
public class TelemetryController {

    private final IngestionService ingestionService;
    private final ExportService exportService;
    private final RetentionService retentionService;
    private final AuditLog auditLog;
    private final StartupRecovery startupRecovery;

    public TelemetryController(IngestionService ingestionService, ExportService exportService,
                               RetentionService retentionService, AuditLog auditLog,
                               StartupRecovery startupRecovery) {
        this.ingestionService = ingestionService;
        this.exportService = exportService;
        this.retentionService = retentionService;
        this.auditLog = auditLog;
        this.startupRecovery = startupRecovery;
    }

    @PostMapping("/events")
    public ResponseEntity<IngestionResult> ingest(@RequestBody EventRequest request) {
        return ResponseEntity.ok(ingestionService.ingest(toEvent(request)));
    }

    @PostMapping("/events/batch")
    public ResponseEntity<List<IngestionResult>> ingestBatch(@RequestBody BatchRequest request) {
        if (request.events() == null) {
            throw new IllegalArgumentException("events 不能为空");
        }
        List<TelemetryEvent> events = request.events().stream().map(this::toEvent).toList();
        return ResponseEntity.ok(ingestionService.ingestBatch(events, request.clientBatchId()));
    }

    @GetMapping("/events")
    public List<StoredEvent> queryEvents(
            @RequestHeader(value = "X-Caller-Id", defaultValue = "anonymous") String callerId,
            @RequestHeader(value = "X-Caller-Role", defaultValue = Caller.ROLE_ANALYST) String role,
            @RequestParam(value = "eventType", required = false) String eventType,
            @RequestParam(value = "raw", defaultValue = "false") boolean raw) {
        return exportService.queryEvents(new Caller(callerId, role), eventType, raw);
    }

    @GetMapping("/aggregates")
    public List<AggregateRow> aggregate(
            @RequestHeader(value = "X-Caller-Id", defaultValue = "anonymous") String callerId,
            @RequestHeader(value = "X-Caller-Role", defaultValue = Caller.ROLE_ANALYST) String role,
            @RequestParam("field") String field,
            @RequestParam(value = "raw", defaultValue = "false") boolean raw) {
        return exportService.aggregate(new Caller(callerId, role), field, raw);
    }

    @PostMapping("/users/delete")
    public Map<String, Object> deleteUsers(@RequestBody Map<String, Object> body) {
        Object rawUsers = body.get("userIds");
        if (!(rawUsers instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("userIds 必须是非空数组");
        }
        Set<String> users = new LinkedHashSet<>();
        list.forEach(u -> users.add(String.valueOf(u)));
        String reason = String.valueOf(body.getOrDefault("reason", "CONSENT_WITHDRAWN"));
        int removed = retentionService.deleteUsers(users, reason);
        return Map.of("deletedUsers", users.size(), "removedEvents", removed);
    }

    @PostMapping("/admin/retention/purge")
    public Map<String, Integer> purgeExpired() {
        return Map.of("removedEvents", retentionService.purgeExpired());
    }

    @GetMapping("/admin/audit")
    public Object audit() {
        return auditLog.entries();
    }

    @GetMapping("/admin/recovery")
    public RecoveryReport recovery() {
        RecoveryReport report = startupRecovery.report();
        if (report == null) {
            return new RecoveryReport(0L, 0L, 0L, List.of(), List.of());
        }
        return report;
    }

    private TelemetryEvent toEvent(EventRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("事件不能为空");
        }
        return new TelemetryEvent(request.eventId(), request.eventType(), request.userId(),
                Boolean.TRUE.equals(request.consent()), request.fields());
    }
}
