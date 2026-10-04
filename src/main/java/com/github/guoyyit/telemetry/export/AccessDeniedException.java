package com.github.guoyyit.telemetry.export;

/** Thrown when a caller requests data beyond its authorization. Always audited. */
public class AccessDeniedException extends RuntimeException {
    public AccessDeniedException(String message) {
        super(message);
    }
}
