package com.github.guoyyit.telemetry.consent;

import java.util.Set;

/** Thread-safe registry of user consent state. */
public interface ConsentRegistry {
    void grant(String userId);

    void withdraw(String userId);

    boolean hasConsent(String userId);

    /** Users whose consent was withdrawn; their stored data must be erased. */
    Set<String> withdrawnUsers();
}
