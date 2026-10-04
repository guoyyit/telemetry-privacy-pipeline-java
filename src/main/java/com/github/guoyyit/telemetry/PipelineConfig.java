package com.github.guoyyit.telemetry;

import com.github.guoyyit.telemetry.policy.CollectionPolicy;
import com.github.guoyyit.telemetry.redact.RedactionRule;
import com.github.guoyyit.telemetry.redact.Redactor;
import com.github.guoyyit.telemetry.redact.RuleBasedRedactor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Configuration
public class PipelineConfig {
    @Bean
    public CollectionPolicy collectionPolicy(
            @Value("${privacy.policy.allowed-event-types:}") String allowedTypes,
            @Value("${privacy.policy.denied-fields:}") String deniedFields) {
        return new CollectionPolicy(split(allowedTypes), split(deniedFields));
    }

    /**
     * Redaction rules from config, e.g.
     * {@code privacy.redaction.rules=email:HASH,deviceId:MASK,internalNote:DROP}
     */
    @Bean
    public Redactor redactor(@Value("${privacy.redaction.rules:}") String rulesSpec,
                             @Value("${privacy.redaction.salt:default-salt}") String salt) {
        List<RedactionRule> rules = new ArrayList<>();
        for (String entry : rulesSpec.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split(":");
            if (parts.length != 2) {
                throw new IllegalArgumentException("invalid redaction rule: " + trimmed);
            }
            rules.add(new RedactionRule(parts[0].trim(), RedactionRule.Action.valueOf(parts[1].trim())));
        }
        return new RuleBasedRedactor(rules, salt);
    }

    @Bean
    public Path walDir(@Value("${privacy.wal.dir:./data/wal}") String dir) {
        return Path.of(dir);
    }

    static Set<String> split(String csv) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : csv.split(",")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return Set.copyOf(out);
    }
}
