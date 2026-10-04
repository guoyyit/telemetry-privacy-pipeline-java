package com.github.guoyyit.telemetry.retention;

import java.time.Instant;

/** Enforces retention period and erasure of withdrawn-consent users. */
public interface RetentionService {
    /** Purge expired data and data of users who withdrew consent. Returns purged count. */
    int enforce(Instant now);
}
