package com.github.guoyyit.telemetry;

import com.github.guoyyit.telemetry.domain.TelemetryEvent;
import com.github.guoyyit.telemetry.redact.RedactionRule;
import com.github.guoyyit.telemetry.redact.RuleBasedRedactor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Same input must yield the same redacted output under any processing order. */
class RedactionDeterminismTest {
    private final List<RedactionRule> rules = List.of(
            new RedactionRule("email", RedactionRule.Action.HASH),
            new RedactionRule("name", RedactionRule.Action.MASK),
            new RedactionRule("password", RedactionRule.Action.DROP));

    private TelemetryEvent event() {
        Map<String, String> fields = new HashMap<>();
        fields.put("email", "alice@example.com");
        fields.put("name", "Alice");
        fields.put("password", "s3cret");
        fields.put("url", "/home");
        return new TelemetryEvent("e1", "u1", "page_view", Instant.parse("2026-01-01T00:00:00Z"), fields);
    }

    @Test
    void redactionIsDeterministicAcrossInstancesAndOrder() {
        TelemetryEvent expected = new RuleBasedRedactor(rules, "salt-1").redact(event());
        // Different redactor instances, and fields inserted in different orders.
        for (int i = 0; i < 50; i++) {
            Map<String, String> shuffled = new HashMap<>();
            final int sign = i % 2 == 0 ? 1 : -1;
            event().fields().entrySet().stream()
                    .sorted((a, b) -> sign * a.getKey().compareTo(b.getKey()))
                    .forEach(e -> shuffled.put(e.getKey(), e.getValue()));
            TelemetryEvent in = new TelemetryEvent("e1", "u1", "page_view",
                    Instant.parse("2026-01-01T00:00:00Z"), shuffled);
            assertEquals(expected, new RuleBasedRedactor(rules, "salt-1").redact(in));
        }
    }

    @Test
    void redactionIsDeterministicUnderConcurrency() {
        TelemetryEvent expected = new RuleBasedRedactor(rules, "salt-1").redact(event());
        RuleBasedRedactor shared = new RuleBasedRedactor(rules, "salt-1");
        IntStream.range(0, 200).parallel().forEach(i ->
                assertEquals(expected, shared.redact(event())));
    }

    @Test
    void maskHashDropSemantics() {
        TelemetryEvent out = new RuleBasedRedactor(rules, "salt-1").redact(event());
        assertEquals("***", out.fields().get("name"));
        assertFalse(out.fields().containsKey("password"));
        assertEquals("/home", out.fields().get("url")); // untouched
        String hash = out.fields().get("email");
        assertNotEquals("alice@example.com", hash);
        assertEquals(64, hash.length()); // HMAC-SHA-256 hex
        // Salt changes the pseudonym.
        TelemetryEvent other = new RuleBasedRedactor(rules, "salt-2").redact(event());
        assertNotEquals(hash, other.fields().get("email"));
    }
}
