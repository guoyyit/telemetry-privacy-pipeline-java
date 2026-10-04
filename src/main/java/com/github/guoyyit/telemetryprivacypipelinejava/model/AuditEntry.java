package com.github.guoyyit.telemetryprivacypipelinejava.model;

/**
 * 合规审计条目：拒绝采集与越权访问等决策均留痕。
 */
public record AuditEntry(
        long timestampMs,
        String actor,
        String action,
        String resource,
        String decision,
        String detail) {
}
