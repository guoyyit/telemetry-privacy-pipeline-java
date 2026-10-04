package com.github.guoyyit.telemetry.export;

import com.github.guoyyit.telemetry.domain.TelemetryEvent;
import com.github.guoyyit.telemetry.redact.Redactor;
import com.github.guoyyit.telemetry.store.EventStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Minimization view at export time. The store holds raw events; STANDARD callers
 * always receive the redacted view, only PRIVILEGED callers may request raw values.
 * Unauthorized raw requests are denied and recorded in the audit log.
 */
@Service
public class DefaultExportService implements ExportService {
    private final EventStore store;
    private final Redactor redactor;
    private final AuditLog auditLog;

    public DefaultExportService(EventStore store, Redactor redactor, AuditLog auditLog) {
        this.store = store;
        this.redactor = redactor;
        this.auditLog = auditLog;
    }

    @Override
    public List<TelemetryEvent> export(CallerContext caller, boolean rawRequested) {
        if (rawRequested && caller.role() != CallerContext.Role.PRIVILEGED) {
            auditLog.record(new AuditLog.Entry(Instant.now(), caller.callerId(),
                    "EXPORT_RAW", false, "raw view requires PRIVILEGED role"));
            throw new AccessDeniedException(
                    "caller " + caller.callerId() + " is not authorized for raw export");
        }
        // One consistent snapshot: the export sees exactly one committed version.
        EventStore.Snapshot snapshot = store.snapshot();
        List<TelemetryEvent> events = snapshot.events();
        if (rawRequested) {
            auditLog.record(new AuditLog.Entry(Instant.now(), caller.callerId(),
                    "EXPORT_RAW", true, "version=" + snapshot.version()));
            return events;
        }
        auditLog.record(new AuditLog.Entry(Instant.now(), caller.callerId(),
                "EXPORT_MINIMIZED", true, "version=" + snapshot.version()));
        return events.stream().map(redactor::redact).toList();
    }
}
