package com.github.guoyyit.telemetry.domain;

/** Outcome of ingesting one event: either accepted into a batch, or dropped with a reason. */
public record IngestionResult(boolean accepted, DropReason dropReason, String batchId) {
    public static IngestionResult accepted(String batchId) {
        return new IngestionResult(true, null, batchId);
    }

    public static IngestionResult dropped(DropReason reason) {
        return new IngestionResult(false, reason, null);
    }
}
