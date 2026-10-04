package com.github.guoyyit.telemetryprivacypipelinejava.retention;

import com.github.guoyyit.telemetryprivacypipelinejava.audit.AuditLog;
import com.github.guoyyit.telemetryprivacypipelinejava.config.PipelineProperties;
import com.github.guoyyit.telemetryprivacypipelinejava.model.AuditEntry;
import com.github.guoyyit.telemetryprivacypipelinejava.model.StoreSnapshot;
import com.github.guoyyit.telemetryprivacypipelinejava.model.StoredEvent;
import com.github.guoyyit.telemetryprivacypipelinejava.store.EventStore;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * {@link RetentionService} 默认实现。
 *
 * <p>删除以“持久化墓碑/清除水位 + 新版本快照”实现，复用 {@link EventStore} 的写锁，
 * 因而与并发采集互斥判定，已删除用户/已过期数据不会被正在进行的写入复活。</p>
 */
@Component
public class RetentionServiceImpl implements RetentionService {

    static final long MILLIS_PER_DAY = 24L * 60L * 60L * 1000L;

    private final EventStore store;
    private final PipelineProperties properties;
    private final AuditLog auditLog;

    public RetentionServiceImpl(EventStore store, PipelineProperties properties, AuditLog auditLog) {
        this.store = store;
        this.properties = properties;
        this.auditLog = auditLog;
    }

    @Override
    public int deleteUsers(Set<String> userIds, String reason) {
        if (userIds == null || userIds.isEmpty()) {
            return 0;
        }
        Set<String> users = new LinkedHashSet<>(userIds);
        users.removeIf(u -> u == null || u.isBlank());
        if (users.isEmpty()) {
            return 0;
        }
        // 先统计将被清除的事件数，再在存储的同一写事务内提交墓碑与新版本。
        int before = countUserEvents(users);
        long now = System.currentTimeMillis();
        store.deleteUsers(reason == null ? "CONSENT_WITHDRAWN" : reason, users, now);
        auditLog.record(new AuditEntry(now, "system", "USER_DELETE",
                "users:" + String.join(",", users), "ALLOW",
                "原因=" + reason + ", 清除事件数=" + before));
        return before;
    }

    @Override
    public int purgeExpired() {
        long now = System.currentTimeMillis();
        long cutoff = now - (long) properties.getRetentionDays() * MILLIS_PER_DAY;
        StoreSnapshot snap = store.snapshot();
        int purged = 0;
        for (StoredEvent event : snap.events()) {
            if (event.timestampMs() < cutoff) {
                purged++;
            }
        }
        // 推进水位是幂等操作：即使本次没有过期事件也安全（存储会忽略未增长的水位）。
        store.advancePurgeCutoff(cutoff, now);
        auditLog.record(new AuditEntry(now, "system", "RETENTION_PURGE",
                "retention:" + properties.getRetentionDays() + "d",
                purged > 0 ? "ALLOW" : "NOOP",
                "cutoff=" + cutoff + ", 清除事件数=" + purged));
        return purged;
    }

    private int countUserEvents(Set<String> users) {
        int count = 0;
        for (StoredEvent event : store.snapshot().events()) {
            if (users.contains(event.userId())) {
                count++;
            }
        }
        return count;
    }
}
