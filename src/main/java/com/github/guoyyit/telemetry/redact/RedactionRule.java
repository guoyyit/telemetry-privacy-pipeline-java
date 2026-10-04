package com.github.guoyyit.telemetry.redact;

/** Field-level de-identification rule. */
public record RedactionRule(String field, Action action) {
    public enum Action {
        /** Replace value with a fixed mask. */
        MASK,
        /** Replace value with a deterministic salted hash (pseudonymization). */
        HASH,
        /** Remove the field entirely. */
        DROP
    }
}
