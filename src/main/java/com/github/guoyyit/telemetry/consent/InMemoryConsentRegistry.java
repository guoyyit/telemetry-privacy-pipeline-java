package com.github.guoyyit.telemetry.consent;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryConsentRegistry implements ConsentRegistry {
    private final Set<String> consented = ConcurrentHashMap.newKeySet();
    private final Set<String> withdrawn = ConcurrentHashMap.newKeySet();

    @Override
    public void grant(String userId) {
        withdrawn.remove(userId);
        consented.add(userId);
    }

    @Override
    public void withdraw(String userId) {
        consented.remove(userId);
        withdrawn.add(userId);
    }

    @Override
    public boolean hasConsent(String userId) {
        return consented.contains(userId) && !withdrawn.contains(userId);
    }

    @Override
    public Set<String> withdrawnUsers() {
        return Set.copyOf(withdrawn);
    }
}
