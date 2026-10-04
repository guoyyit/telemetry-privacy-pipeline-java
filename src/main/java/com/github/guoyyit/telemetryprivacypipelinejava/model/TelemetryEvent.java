package com.github.guoyyit.telemetryprivacypipelinejava.model;

import java.util.Map;

/**
 * 客户端上报的一条遥测事件（管道输入）。
 */
public record TelemetryEvent(
        String eventId,
        String eventType,
        String userId,
        boolean consent,
        Map<String, String> fields) {
}
