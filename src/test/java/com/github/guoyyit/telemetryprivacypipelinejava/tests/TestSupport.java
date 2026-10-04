package com.github.guoyyit.telemetryprivacypipelinejava.tests;

import com.github.guoyyit.telemetryprivacypipelinejava.audit.AuditLog;
import com.github.guoyyit.telemetryprivacypipelinejava.audit.FileAuditLog;
import com.github.guoyyit.telemetryprivacypipelinejava.config.PipelineProperties;
import com.github.guoyyit.telemetryprivacypipelinejava.export.Aggregator;
import com.github.guoyyit.telemetryprivacypipelinejava.export.ExportServiceImpl;
import com.github.guoyyit.telemetryprivacypipelinejava.export.ViewProjector;
import com.github.guoyyit.telemetryprivacypipelinejava.ingest.IngestionServiceImpl;
import com.github.guoyyit.telemetryprivacypipelinejava.policy.FieldSanitizer;
import com.github.guoyyit.telemetryprivacypipelinejava.policy.PolicyFactory;
import com.github.guoyyit.telemetryprivacypipelinejava.retention.RetentionServiceImpl;
import com.github.guoyyit.telemetryprivacypipelinejava.store.DurableEventLog;
import com.github.guoyyit.telemetryprivacypipelinejava.store.EventStoreImpl;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 测试装配工具：为每个测试提供独立临时数据目录的完整管道组件。
 */
final class TestSupport {

    private TestSupport() {
    }

    static PipelineProperties props(Path dataDir, int minGroupSize, boolean merge) {
        PipelineProperties p = new PipelineProperties();
        p.setDataDir(dataDir.toString());
        p.setRetentionDays(90);
        p.setMinGroupSize(minGroupSize);
        p.setMergeSuppressedGroups(merge);
        p.setAllowedEventTypes(List.of("page_view", "click"));
        p.setFieldRules(new java.util.LinkedHashMap<>(Map.of(
                "email", "HASH",
                "phone", "MASK",
                "id_card", "DROP",
                "content", "KEEP")));
        p.setDeniedFields(List.of("health_data"));
        return p;
    }

    static PipelineProps bundle(Path dataDir) {
        return bundle(dataDir, 2, true);
    }

    static PipelineProps bundle(Path dataDir, int minGroupSize, boolean merge) {
        PipelineProperties props = props(dataDir, minGroupSize, merge);
        DurableEventLog log = new DurableEventLog(props);
        EventStoreImpl store = new EventStoreImpl(log);
        AuditLog audit = new FileAuditLog(props);
        PolicyFactory factory = new PolicyFactory();
        FieldSanitizer sanitizer = new FieldSanitizer();
        IngestionServiceImpl ingest = new IngestionServiceImpl(
                store, factory, sanitizer, audit, props);
        RetentionServiceImpl retention = new RetentionServiceImpl(store, props, audit);
        ViewProjector projector = new ViewProjector(audit);
        Aggregator aggregator = new Aggregator();
        ExportServiceImpl export = new ExportServiceImpl(
                store, projector, aggregator, audit, props);
        return new PipelineProps(props, log, store, audit, ingest, retention, export);
    }

    record PipelineProps(
            PipelineProperties properties,
            DurableEventLog log,
            EventStoreImpl store,
            AuditLog audit,
            IngestionServiceImpl ingest,
            RetentionServiceImpl retention,
            ExportServiceImpl export) {
    }
}
