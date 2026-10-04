package com.github.guoyyit.telemetry.domain;

import java.time.Instant;
import java.util.Map;

/** A single client telemetry event. {@code fields} holds arbitrary key/value payload. */
public record TelemetryEvent(String eventId, String userId, String eventType, Instant timestamp,
                             Map<String, String> fields) {
}
