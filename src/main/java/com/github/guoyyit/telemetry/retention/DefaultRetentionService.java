package com.github.guoyyit.telemetry.retention;

import com.github.guoyyit.telemetry.consent.ConsentRegistry;
import com.github.guoyyit.telemetry.store.EventStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Enforces two erasure duties: (1) events older than the retention period are purged,
 * (2) all events of users who withdrew consent are deleted. Both go through the
 * store's tombstone mechanism, so data erased here cannot be resurrected by
 * concurrent in-flight writes.
 */
@Service
public class DefaultRetentionService implements RetentionService {
    private final EventStore store;
    private final ConsentRegistry consentRegistry;
    private final Duration retentionPeriod;

    public DefaultRetentionService(EventStore store, ConsentRegistry consentRegistry,
                                   @Value("${privacy.retention-period:PT720H}") Duration retentionPeriod) {
        this.store = store;
        this.consentRegistry = consentRegistry;
        this.retentionPeriod = retentionPeriod;
    }

    @Override
    public int enforce(Instant now) {
        int purged = store.purgeBefore(now.minus(retentionPeriod));
        for (String userId : consentRegistry.withdrawnUsers()) {
            purged += store.deleteUser(userId);
        }
        return purged;
    }
}
