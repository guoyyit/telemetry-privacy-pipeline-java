package com.github.guoyyit.telemetryprivacypipelinejava.tests;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 端到端 REST 测试：采集拒绝、最小化视图、越权 403 留痕、聚合导出、删除与审计查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebApiIntegrationTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("pipeline.data-dir", tempDir::toString);
        registry.add("pipeline.min-group-size", () -> 2);
        registry.add("pipeline.allowed-event-types", () -> List.of("page_view", "click"));
        registry.add("pipeline.denied-fields", () -> List.of("health_data"));
        registry.add("pipeline.field-rules.email", () -> "HASH");
        registry.add("pipeline.field-rules.content", () -> "KEEP");
    }

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fullPipelineFlowOverHttp() throws Exception {
        // 未同意事件被拒绝
        mockMvc.perform(post("/api/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "eventId", "n1", "eventType", "page_view",
                                "userId", "u1", "consent", false,
                                "fields", Map.of("content", "x")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.reason").value("NO_CONSENT"));

        // 合法事件（同内容重复两次以便形成可发布聚合分组）
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/events")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of(
                                    "eventId", "ok" + i, "eventType", "page_view",
                                    "userId", "u1", "consent", true,
                                    "fields", Map.of("content", "hello",
                                            "email", "a@x.com")))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accepted").value(true));
        }

        // 分析员看到脱敏视图（邮箱为 64 位摘要）
        mockMvc.perform(get("/api/events")
                        .header("X-Caller-Id", "an1")
                        .header("X-Caller-Role", "ANALYST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].userId").doesNotExist());

        // 分析员越权请求 raw => 403
        mockMvc.perform(get("/api/events").param("raw", "true")
                        .header("X-Caller-Id", "an1")
                        .header("X-Caller-Role", "ANALYST"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));

        // 管理员 raw 请求成功
        mockMvc.perform(get("/api/events").param("raw", "true")
                        .header("X-Caller-Id", "admin")
                        .header("X-Caller-Role", "PRIVACY_ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rawFields.email").value("a@x.com"));

        // 聚合导出（content=hello 计数 2，满足最小分组 2）
        mockMvc.perform(get("/api/aggregates").param("field", "content")
                        .header("X-Caller-Id", "an1")
                        .header("X-Caller-Role", "ANALYST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].groupKey").value("hello"))
                .andExpect(jsonPath("$[0].count").value(2));

        // 撤回同意删除
        mockMvc.perform(post("/api/users/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "userIds", List.of("u1"),
                                "reason", "CONSENT_WITHDRAWN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removedEvents").value(2));

        // 审计中可看到拒绝采集与越权访问的留痕
        mockMvc.perform(get("/api/admin/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action=='EVENT_DISCARD' && @.decision=='DENY')]")
                        .isNotEmpty())
                .andExpect(jsonPath("$[?(@.action=='RAW_VIEW_DENIED')]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.action=='USER_DELETE')]").isNotEmpty());
    }
}
