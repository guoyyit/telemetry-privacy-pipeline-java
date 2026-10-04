package com.github.guoyyit.telemetryprivacypipelinejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.github.guoyyit.telemetryprivacypipelinejava.model.StoredEvent;
import com.github.guoyyit.telemetryprivacypipelinejava.model.TelemetryEvent;
import com.github.guoyyit.telemetryprivacypipelinejava.policy.CollectionPolicy;
import com.github.guoyyit.telemetryprivacypipelinejava.policy.FieldSanitizer;
import com.github.guoyyit.telemetryprivacypipelinejava.policy.PolicyFactory;
import com.github.guoyyit.telemetryprivacypipelinejava.tests.TestSupport.PipelineProps;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 场景 2：脱敏与去标识。掩码/摘要/删除规则正确应用，且无论字段处理顺序如何，
 * 结果完全一致（确定性、顺序无关）。
 */
class SanitizationConsistencyTest {

    @TempDir
    Path tempDir;

    @Test
    void appliesMaskHashAndDrop() {
        PipelineProps p = TestSupport.bundle(tempDir);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("email", "alice@example.com");
        fields.put("phone", "13800000000");
        fields.put("id_card", "110101199001011234");
        fields.put("content", "hello");

        p.ingest().ingest(new TelemetryEvent("e1", "page_view", "u1", true, fields));
        StoredEvent stored = p.store().snapshot().events().get(0);

        // HASH：确定性 SHA-256，不含原文
        assertEquals(FieldSanitizer.sha256("alice@example.com"), stored.redactedFields().get("email"));
        assertFalse(stored.redactedFields().get("email").contains("alice"));
        // MASK：固定掩码
        assertEquals(FieldSanitizer.MASK_TOKEN, stored.redactedFields().get("phone"));
        // DROP：字段彻底消失
        assertFalse(stored.redactedFields().containsKey("id_card"));
        // KEEP：原文保留
        assertEquals("hello", stored.redactedFields().get("content"));
        // 原始值仅在 rawFields 中保留
        assertEquals("110101199001011234", stored.rawFields().get("id_card"));
    }

    @Test
    void resultIndependentOfInputOrderAndProcessingOrder() {
        PipelineProps p = TestSupport.bundle(tempDir);
        FieldSanitizer sanitizer = new FieldSanitizer();
        PolicyFactory factory = new PolicyFactory();
        CollectionPolicy policy = factory.create(p.properties());

        Map<String, String> orderA = new LinkedHashMap<>();
        orderA.put("email", "a@x.com");
        orderA.put("phone", "123");
        orderA.put("content", "hi");

        // 相同内容，完全相反的迭代顺序
        Map<String, String> orderB = new LinkedHashMap<>();
        orderB.put("content", "hi");
        orderB.put("phone", "123");
        orderB.put("email", "a@x.com");

        Map<String, String> r1 = sanitizer.sanitize(orderA, policy);
        Map<String, String> r2 = sanitizer.sanitize(orderB, policy);

        assertEquals(r1, r2);
        assertEquals("[content, email, phone]", r1.keySet().toString());

        // 跨批次：同一输入在任何处理顺序/时刻结果一致（无随机盐）
        Map<String, String> shuffled = new HashMap<>(orderB);
        Map<String, String> r3 = sanitizer.sanitize(shuffled, policy);
        assertEquals(r1, r3);
        assertNotEquals("a@x.com", r3.get("email"));
        // 相同原始值永远映射到相同摘要
        assertEquals(r1.get("email"),
                sanitizer.sanitize(Map.of("email", "a@x.com"), policy).get("email"));
    }

    @Test
    void storedAndReingestedRedactionAreIdentical() {
        PipelineProps p = TestSupport.bundle(tempDir);
        Map<String, String> fields = Map.of("email", "bob@example.com", "phone", "999");
        p.ingest().ingest(new TelemetryEvent("a", "click", "u2", true, fields));
        StoredEvent first = p.store().snapshot().events().get(0);

        // 同值再次上报（模拟任意处理顺序），脱敏结果必须逐字节一致
        p.ingest().ingest(new TelemetryEvent("b", "click", "u2", true,
                new LinkedHashMap<>(fields)));
        StoredEvent second = p.store().snapshot().events().get(1);
        assertEquals(first.redactedFields(), second.redactedFields());
    }
}
