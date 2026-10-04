package com.github.guoyyit.telemetry;

import com.github.guoyyit.telemetry.domain.TelemetryEvent;
import com.github.guoyyit.telemetry.export.AccessDeniedException;
import com.github.guoyyit.telemetry.export.CallerContext;
import com.github.guoyyit.telemetry.export.DefaultExportService;
import com.github.guoyyit.telemetry.export.InMemoryAuditLog;
import com.github.guoyyit.telemetry.redact.RedactionRule;
import com.github.guoyyit.telemetry.redact.RuleBasedRedactor;
import com.github.guoyyit.telemetry.store.VersionedEventStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Only privileged callers may see raw values; violations are denied and audited. */
class ExportAuthorizationTest {
    VersionedEventStore store;
    InMemoryAuditLog auditLog;
    DefaultExportService exportService;

    @BeforeEach
    void setUp() {
        store = new VersionedEventStore();
        store.commit("b1", List.of(new TelemetryEvent("e1", "u1", "page_view",
                Instant.parse("2026-01-01T00:00:00Z"),
                Map.of("email", "alice@example.com", "url", "/home"))));
        auditLog = new InMemoryAuditLog();
        exportService = new DefaultExportService(store,
                new RuleBasedRedactor(List.of(new RedactionRule("email", RedactionRule.Action.HASH)), "s"),
                auditLog);
    }

    @Test
    void standardCallerGetsMinimizedView() {
        List<TelemetryEvent> out = exportService.export(
                new CallerContext("analyst", CallerContext.Role.STANDARD), false);
        assertEquals(1, out.size());
        assertTrue(out.get(0).fields().get("email").matches("[0-9a-f]{64}"));
        assertEquals("/home", out.get(0).fields().get("url"));
    }

    @Test
    void privilegedCallerGetsRawView() {
        List<TelemetryEvent> out = exportService.export(
                new CallerContext("dpo", CallerContext.Role.PRIVILEGED), true);
        assertEquals("alice@example.com", out.get(0).fields().get("email"));
    }

    @Test
    void standardCallerRequestingRawIsDeniedAndAudited() {
        CallerContext intruder = new CallerContext("analyst", CallerContext.Role.STANDARD);
        assertThrows(AccessDeniedException.class, () -> exportService.export(intruder, true));
        assertTrue(auditLog.entries().stream().anyMatch(e ->
                !e.allowed() && e.callerId().equals("analyst") && e.action().equals("EXPORT_RAW")));
    }
}
