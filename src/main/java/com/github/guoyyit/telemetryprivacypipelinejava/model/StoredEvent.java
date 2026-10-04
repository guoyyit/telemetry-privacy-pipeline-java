package com.github.guoyyit.telemetryprivacypipelinejava.model;

import java.util.Map;

/**
 * 已提交进入存储的事件。rawFields 仅授权角色可见，redactedFields 为最小化视图。
 */
public record StoredEvent(
        long seq,
        String eventId,
        String eventType,
        String userId,
        long timestampMs,
        String batchId,
        Map<String, String> rawFields,
        Map<String, String> redactedFields) {
}
