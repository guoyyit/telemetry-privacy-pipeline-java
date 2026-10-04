package com.github.guoyyit.telemetry.export;

/** Identity and privilege of an export/query caller. */
public record CallerContext(String callerId, Role role) {
    public enum Role {
        /** May see raw (unredacted) values. */
        PRIVILEGED,
        /** Sees only the minimized, redacted view. */
        STANDARD
    }
}
