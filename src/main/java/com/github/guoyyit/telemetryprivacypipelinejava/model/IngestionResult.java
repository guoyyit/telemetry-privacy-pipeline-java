package com.github.guoyyit.telemetryprivacypipelinejava.model;

/**
 * 单条事件的采集结果：要么被接受并赋序号，要么带可区分原因被拒绝。
 */
public record IngestionResult(
        boolean accepted,
        String eventId,
        long seq,
        DiscardReason reason,
        String detail) {

    public static IngestionResult accepted(String eventId, long seq) {
        return new IngestionResult(true, eventId, seq, null, null);
    }

    public static IngestionResult rejected(String eventId, DiscardReason reason, String detail) {
        return new IngestionResult(false, eventId, -1L, reason, detail);
    }
}
