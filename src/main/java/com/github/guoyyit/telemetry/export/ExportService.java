package com.github.guoyyit.telemetry.export;

import com.github.guoyyit.telemetry.domain.TelemetryEvent;

import java.util.List;

/**
 * Applies the minimization view at export/query time. Only PRIVILEGED callers may see
 * raw values; unauthorized raw access is denied and audited.
 */
public interface ExportService {
    /** Export events visible to the caller. Raw view requires PRIVILEGED role. */
    List<TelemetryEvent> export(CallerContext caller, boolean rawRequested);
}
