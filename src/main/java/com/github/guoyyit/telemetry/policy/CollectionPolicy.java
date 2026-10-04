package com.github.guoyyit.telemetry.policy;

import java.util.Set;

/**
 * Decides which event types and fields may be collected at all. An empty
 * {@code allowedEventTypes} means "all types allowed"; {@code deniedFields} always win.
 */
public record CollectionPolicy(Set<String> allowedEventTypes, Set<String> deniedFields) {
    public CollectionPolicy {
        allowedEventTypes = allowedEventTypes == null ? Set.of() : Set.copyOf(allowedEventTypes);
        deniedFields = deniedFields == null ? Set.of() : Set.copyOf(deniedFields);
    }

    public boolean isEventTypeAllowed(String eventType) {
        return allowedEventTypes.isEmpty() || allowedEventTypes.contains(eventType);
    }

    public boolean isFieldAllowed(String field) {
        return !deniedFields.contains(field);
    }
}
