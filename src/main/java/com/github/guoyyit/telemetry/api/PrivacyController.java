package com.github.guoyyit.telemetry.api;

import com.github.guoyyit.telemetry.consent.ConsentRegistry;
import com.github.guoyyit.telemetry.retention.RetentionService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/privacy")
public class PrivacyController {
    private final ConsentRegistry consentRegistry;
    private final RetentionService retentionService;

    public PrivacyController(ConsentRegistry consentRegistry, RetentionService retentionService) {
        this.consentRegistry = consentRegistry;
        this.retentionService = retentionService;
    }

    @PostMapping("/consent/{userId}")
    public Map<String, Object> grant(@PathVariable String userId) {
        consentRegistry.grant(userId);
        return Map.of("userId", userId, "consent", "granted");
    }

    @DeleteMapping("/consent/{userId}")
    public Map<String, Object> withdraw(@PathVariable String userId) {
        consentRegistry.withdraw(userId);
        return Map.of("userId", userId, "consent", "withdrawn");
    }

    @PostMapping("/retention/enforce")
    public Map<String, Object> enforceRetention() {
        return Map.of("purged", retentionService.enforce(Instant.now()));
    }
}
