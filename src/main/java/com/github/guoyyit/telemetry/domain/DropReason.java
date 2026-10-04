package com.github.guoyyit.telemetry.domain;

/** Distinguishable, traceable reasons why an event was refused before reaching storage. */
public enum DropReason {
    NO_CONSENT,
    CONSENT_WITHDRAWN,
    EVENT_TYPE_DENIED,
    FIELD_POLICY_DENIED
}
