package com.github.guoyyit.telemetry.redact;

import com.github.guoyyit.telemetry.domain.TelemetryEvent;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic field-level redactor. Hashing uses HMAC-SHA-256 with a fixed salt and
 * includes the field name, so the same input always yields the same output regardless
 * of processing order, thread, or JVM instance.
 */
public class RuleBasedRedactor implements Redactor {
    static final String MASK_VALUE = "***";

    private final Map<String, RedactionRule.Action> actionsByField;
    private final byte[] salt;

    public RuleBasedRedactor(List<RedactionRule> rules, String hashSalt) {
        this.actionsByField = new HashMap<>();
        for (RedactionRule rule : rules) {
            actionsByField.put(rule.field(), rule.action());
        }
        this.salt = hashSalt.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public TelemetryEvent redact(TelemetryEvent event) {
        Map<String, String> redacted = new HashMap<>();
        for (Map.Entry<String, String> e : event.fields().entrySet()) {
            RedactionRule.Action action = actionsByField.get(e.getKey());
            if (action == null) {
                redacted.put(e.getKey(), e.getValue());
            } else {
                switch (action) {
                    case MASK -> redacted.put(e.getKey(), MASK_VALUE);
                    case HASH -> redacted.put(e.getKey(), hash(e.getKey(), e.getValue()));
                    case DROP -> { /* field removed */ }
                }
            }
        }
        return new TelemetryEvent(event.eventId(), event.userId(), event.eventType(),
                event.timestamp(), Map.copyOf(redacted));
    }

    private String hash(String field, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt, "HmacSHA256"));
            byte[] digest = mac.doFinal((field + "=" + value).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 unavailable", ex);
        }
    }
}
