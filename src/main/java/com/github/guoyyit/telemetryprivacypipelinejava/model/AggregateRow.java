package com.github.guoyyit.telemetryprivacypipelinejava.model;

/**
 * 聚合导出行。other=true 表示由低于最小分组规模的分组确定性合并而成。
 */
public record AggregateRow(String groupKey, long count, boolean other) {
}
