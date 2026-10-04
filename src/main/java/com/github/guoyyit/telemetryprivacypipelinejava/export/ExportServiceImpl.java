package com.github.guoyyit.telemetryprivacypipelinejava.export;

import com.github.guoyyit.telemetryprivacypipelinejava.audit.AuditLog;
import com.github.guoyyit.telemetryprivacypipelinejava.config.PipelineProperties;
import com.github.guoyyit.telemetryprivacypipelinejava.model.AggregateRow;
import com.github.guoyyit.telemetryprivacypipelinejava.model.StoreSnapshot;
import com.github.guoyyit.telemetryprivacypipelinejava.model.StoredEvent;
import com.github.guoyyit.telemetryprivacypipelinejava.store.EventStore;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@link ExportService} 的默认实现。
 *
 * <p>每次导出抓取一次当前已提交的不可变快照，之后的并发写入不会影响本次导出，
 * 慢导出只持有快照引用，不阻塞写入。</p>
 */
@Component
public class ExportServiceImpl implements ExportService {

    private final EventStore store;
    private final ViewProjector projector;
    private final Aggregator aggregator;
    private final AuditLog auditLog;
    private final PipelineProperties properties;

    public ExportServiceImpl(EventStore store, ViewProjector projector, Aggregator aggregator,
                             AuditLog auditLog, PipelineProperties properties) {
        this.store = store;
        this.projector = projector;
        this.aggregator = aggregator;
        this.auditLog = auditLog;
        this.properties = properties;
    }

    @Override
    public List<StoredEvent> queryEvents(Caller caller, String eventType, boolean rawRequested) {
        StoreSnapshot snap = store.snapshot();
        List<StoredEvent> visible = projector.project(snap, caller, rawRequested);
        List<StoredEvent> filtered = new java.util.ArrayList<>();
        for (StoredEvent event : visible) {
            if (eventType == null || eventType.isBlank() || eventType.equals(event.eventType())) {
                filtered.add(event);
            }
        }
        filtered.sort(Comparator.comparingLong(StoredEvent::seq));
        return List.copyOf(filtered);
    }

    @Override
    public List<AggregateRow> aggregate(Caller caller, String field, boolean rawRequested) {
        StoreSnapshot snap = store.snapshot();
        // 先投影再聚合：越权请求在投影阶段被拒绝并留痕，聚合永远不会越过最小化视图。
        List<StoredEvent> visible = projector.project(snap, caller, rawRequested);
        List<AggregateRow> rows = aggregator.aggregate(visible, field,
                properties.getMinGroupSize(), properties.isMergeSuppressedGroups());
        auditLog.record(new com.github.guoyyit.telemetryprivacypipelinejava.model.AuditEntry(
                System.currentTimeMillis(),
                caller.id(),
                "AGGREGATE_EXPORT",
                "field:" + field,
                "ALLOW",
                "最小分组规模=" + properties.getMinGroupSize() + ", 输出行数=" + rows.size()
                        + ", raw=" + rawRequested));
        return rows;
    }
}
