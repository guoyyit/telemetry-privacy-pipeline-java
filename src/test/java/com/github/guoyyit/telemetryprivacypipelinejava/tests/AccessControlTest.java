package com.github.guoyyit.telemetryprivacypipelinejava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.guoyyit.telemetryprivacypipelinejava.export.AccessDeniedException;
import com.github.guoyyit.telemetryprivacypipelinejava.export.Caller;
import com.github.guoyyit.telemetryprivacypipelinejava.model.StoredEvent;
import com.github.guoyyit.telemetryprivacypipelinejava.model.TelemetryEvent;
import com.github.guoyyit.telemetryprivacypipelinejava.tests.TestSupport.PipelineProps;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 场景 3：最小化视图与越权。仅 PRIVACY_ADMIN 可在 raw=true 时看到原始值；
 * 其余角色越权请求必须被拒绝（403 语义）并在审计中留痕。
 */
class AccessControlTest {

    @TempDir
    Path tempDir;

    private PipelineProps seeded() {
        PipelineProps p = TestSupport.bundle(tempDir);
        p.ingest().ingest(new TelemetryEvent("e1", "page_view", "u-secret",
                true, Map.of("email", "alice@example.com", "content", "hi")));
        return p;
    }

    @Test
    void analystSeesOnlyMinimizedView() {
        PipelineProps p = seeded();
        List<StoredEvent> view = p.export().queryEvents(
                new Caller("analyst-1", Caller.ROLE_ANALYST), null, false);

        assertEquals(1, view.size());
        // 用户标识被移除；邮箱只以摘要存在
        assertNull(view.get(0).userId());
        assertEquals(64, view.get(0).redactedFields().get("email").length());
        assertEquals(view.get(0).rawFields(), view.get(0).redactedFields());
    }

    @Test
    void privacyAdminCanRequestRawAndIsAudited() {
        PipelineProps p = seeded();
        List<StoredEvent> view = p.export().queryEvents(
                new Caller("admin-1", Caller.ROLE_PRIVACY_ADMIN), null, true);

        assertEquals("alice@example.com", view.get(0).rawFields().get("email"));
        assertEquals("u-secret", view.get(0).userId());
        assertTrue(p.audit().entries().stream()
                .anyMatch(e -> "RAW_VIEW_GRANTED".equals(e.action())
                        && "admin-1".equals(e.actor())));
    }

    @Test
    void analystRawRequestIsDeniedAndAudited() {
        PipelineProps p = seeded();
        Caller analyst = new Caller("analyst-2", Caller.ROLE_ANALYST);

        AccessDeniedException ex = assertThrows(AccessDeniedException.class,
                () -> p.export().queryEvents(analyst, null, true));
        assertEquals("events", ex.resource());

        // 越权请求同样不得在聚合路径上绕过最小化视图
        assertThrows(AccessDeniedException.class,
                () -> p.export().aggregate(analyst, "email", true));

        long denies = p.audit().entries().stream()
                .filter(e -> "RAW_VIEW_DENIED".equals(e.action())
                        && "analyst-2".equals(e.actor())
                        && "DENY".equals(e.decision()))
                .count();
        assertEquals(2, denies);
    }

    @Test
    void serviceRoleRawRequestIsAlsoDenied() {
        PipelineProps p = seeded();
        assertThrows(AccessDeniedException.class, () -> p.export().aggregate(
                new Caller("svc", Caller.ROLE_SERVICE), "content", true));
    }
}
