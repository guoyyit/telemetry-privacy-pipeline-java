package com.github.guoyyit.telemetryprivacypipelinejava.web.dto;

import java.util.List;
import java.util.Map;

/**
 * 采集接口 DTO。
 */
public final class EventDtos {

    private EventDtos() {
    }

    public record EventRequest(
            String eventId,
            String eventType,
            String userId,
            Boolean consent,
            Map<String, String> fields) {
    }

    public record BatchRequest(String clientBatchId, List<EventRequest> events) {
    }
}
