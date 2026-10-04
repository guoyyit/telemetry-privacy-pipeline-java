package com.github.guoyyit.telemetryprivacypipelinejava.ingest;

import com.github.guoyyit.telemetryprivacypipelinejava.audit.AuditLog;
import com.github.guoyyit.telemetryprivacypipelinejava.config.PipelineProperties;
import com.github.guoyyit.telemetryprivacypipelinejava.model.AuditEntry;
import com.github.guoyyit.telemetryprivacypipelinejava.model.DiscardReason;
import com.github.guoyyit.telemetryprivacypipelinejava.model.IngestionResult;
import com.github.guoyyit.telemetryprivacypipelinejava.model.StoredEvent;
import com.github.guoyyit.telemetryprivacypipelinejava.model.TelemetryEvent;
import com.github.guoyyit.telemetryprivacypipelinejava.policy.CollectionPolicy;
import com.github.guoyyit.telemetryprivacypipelinejava.policy.FieldSanitizer;
import com.github.guoyyit.telemetryprivacypipelinejava.policy.PolicyFactory;
import com.github.guoyyit.telemetryprivacypipelinejava.store.EventStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@link IngestionService} 默认实现。
 *
 * <p>处理顺序固定为：基本校验 -&gt; 同意/类型策略 -&gt; 字段拒绝规则 -&gt; 撤回墓碑判定
 * -&gt; 字段脱敏 -&gt; 单一原子批次提交。每种拒绝原因（{@link DiscardReason}）可区分，
 * 且拒绝事件连同原因写入审计日志，数据不会进入存储。</p>
 */
@Component
public class IngestionServiceImpl implements IngestionService {

    private final EventStore store;
    private final PolicyFactory policyFactory;
    private final FieldSanitizer sanitizer;
    private final AuditLog auditLog;
    private final PipelineProperties properties;

    public IngestionServiceImpl(EventStore store, PolicyFactory policyFactory,
                                FieldSanitizer sanitizer, AuditLog auditLog,
                                PipelineProperties properties) {
        this.store = store;
        this.policyFactory = policyFactory;
        this.sanitizer = sanitizer;
        this.auditLog = auditLog;
        this.properties = properties;
    }

    @Override
    public IngestionResult ingest(TelemetryEvent event) {
        return ingestBatch(List.of(event), null).get(0);
    }

    @Override
    public List<IngestionResult> ingestBatch(List<TelemetryEvent> events, String clientBatchId) {
        if (events == null || events.isEmpty()) {
            throw new IllegalArgumentException("采集批次不能为空");
        }
        String batchId = (clientBatchId == null || clientBatchId.isBlank())
                ? "batch-" + UUID.randomUUID()
                : clientBatchId.trim();
        CollectionPolicy policy = policyFactory.create(properties);
        long now = System.currentTimeMillis();

        List<IngestionResult> results = new ArrayList<>(events.size());
        List<StoredEvent> accepted = new ArrayList<>();
        for (TelemetryEvent event : events) {
            IngestionResult checked = check(event, policy);
            results.add(checked);
            if (checked.accepted()) {
                accepted.add(toStoredEvent(event, policy, now, batchId));
            }
        }

        if (!accepted.isEmpty()) {
            // 所有接受事件在一个批次内原子提交：要么整批进入某个新版本，要么一条都不可见。
            // 全局序号由存储在写锁内分配；提交后按 eventId 从新版本回填结果序号。
            store.appendBatch(batchId, accepted);
            Map<String, Long> assigned = new HashMap<>();
            store.snapshot().events().stream()
                    .filter(e -> batchId.equals(e.batchId()))
                    .forEach(e -> assigned.put(e.eventId(), e.seq()));
            for (int i = 0; i < results.size(); i++) {
                IngestionResult r = results.get(i);
                if (r.accepted()) {
                    results.set(i, IngestionResult.accepted(r.eventId(),
                            assigned.getOrDefault(r.eventId(), -1L)));
                }
            }
        }
        return List.copyOf(results);
    }

    private IngestionResult check(TelemetryEvent event, CollectionPolicy policy) {
        String eventId = event.eventId();
        if (eventId == null || eventId.isBlank()
                || event.userId() == null || event.userId().isBlank()) {
            return reject(eventId, DiscardReason.INVALID_EVENT, "缺少 eventId 或 userId");
        }

        DiscardReason reason = policy.evaluateConsentAndType(event.eventType(),
                Boolean.TRUE.equals(event.consent()));
        if (reason != null) {
            return reject(eventId, reason,
                    reason == DiscardReason.NO_CONSENT ? "用户未同意采集" : "事件类型未在采集策略中");
        }

        String deniedField = policy.findDeniedField(event.fields());
        if (deniedField != null) {
            return reject(eventId, DiscardReason.DENY_RULE, "命中拒绝字段: " + deniedField);
        }

        // 撤回墓碑判定必须在提交前：已删除用户重新上报的数据一律拒绝，杜绝复活。
        if (store.isDeleted(event.userId())) {
            return reject(eventId, DiscardReason.CONSENT_WITHDRAWN,
                    "用户已撤回同意/已删除，数据不得进入存储");
        }

        // 序号在存储写锁内分配，此处仅表示“接受”。
        return IngestionResult.accepted(eventId, -1L);
    }

    private IngestionResult reject(String eventId, DiscardReason reason, String detail) {
        auditLog.record(new AuditEntry(System.currentTimeMillis(), "ingest",
                "EVENT_DISCARD", "event:" + eventId, "DENY",
                "reason=" + reason + ", " + detail));
        return IngestionResult.rejected(eventId, reason, detail);
    }

    private StoredEvent toStoredEvent(TelemetryEvent event, CollectionPolicy policy, long now,
                                      String batchId) {
        Map<String, String> raw = event.fields() == null
                ? Map.of() : Map.copyOf(event.fields());
        Map<String, String> redacted = sanitizer.sanitize(raw, policy);
        // seq 传 0 占位，EventStore 提交时在写锁内重新分配全局连续序号。
        return new StoredEvent(0L, event.eventId(), event.eventType(), event.userId(),
                now, batchId, raw, redacted);
    }
}
