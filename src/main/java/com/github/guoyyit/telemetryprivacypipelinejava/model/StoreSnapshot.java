package com.github.guoyyit.telemetryprivacypipelinejava.model;

import java.util.List;
import java.util.Set;

/**
 * 某个已提交版本的不可变快照；读者在任一时刻看到的都是完整版本，不会出现字段级混合。
 */
public record StoreSnapshot(
        long version,
        long timestampMs,
        List<StoredEvent> events,
        Set<String> deletedUsers) {

    public static StoreSnapshot empty() {
        return new StoreSnapshot(0L, 0L, List.of(), Set.of());
    }
}
