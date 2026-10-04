package com.github.guoyyit.telemetry.redact;

import com.github.guoyyit.telemetry.domain.TelemetryEvent;

/**
 * Applies field-level redaction. Must be deterministic: the same input event yields the
 * same redacted output regardless of processing order or thread.
 */
public interface Redactor {
    TelemetryEvent redact(TelemetryEvent event);
}
