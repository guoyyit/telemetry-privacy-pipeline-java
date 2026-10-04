package com.github.guoyyit.telemetryprivacypipelinejava.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.telemetryprivacypipelinejava.config.PipelineProperties;
import com.github.guoyyit.telemetryprivacypipelinejava.model.AuditEntry;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/**
 * 同步落盘的审计日志（{@code audit.log}，按行 JSON，每条 fsync）。
 *
 * <p>拒绝采集、越权访问、删除与保留期清除等安全决策均在此留痕；
 * 内存仅做读缓存，真实凭据是磁盘文件，重启后可通过 {@link #reload()} 重新载入。</p>
 */
@Component
public class FileAuditLog implements AuditLog {

    static final String AUDIT_FILE = "audit.log";

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private final CopyOnWriteArrayList<AuditEntry> entries = new CopyOnWriteArrayList<>();

    public FileAuditLog(PipelineProperties properties) {
        Path dir = Path.of(properties.getDataDir());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建数据目录: " + dir, e);
        }
        this.file = dir.resolve(AUDIT_FILE);
        reload();
    }

    @Override
    public void record(AuditEntry entry) {
        // 先落盘后入内存，保证“留痕”先于后续动作发生。
        Map<String, Object> rec = new java.util.LinkedHashMap<>();
        rec.put("ts", entry.timestampMs());
        rec.put("actor", entry.actor());
        rec.put("action", entry.action());
        rec.put("resource", entry.resource());
        rec.put("decision", entry.decision());
        rec.put("detail", entry.detail());
        try {
            byte[] bytes = (mapper.writeValueAsString(rec) + System.lineSeparator())
                    .getBytes(StandardCharsets.UTF_8);
            synchronized (this) {
                try (FileOutputStream out = new FileOutputStream(file.toFile(), true)) {
                    out.write(bytes);
                    out.getFD().sync();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("写入审计日志失败: " + file, e);
        }
        entries.add(entry);
    }

    @Override
    public List<AuditEntry> entries() {
        return List.copyOf(entries);
    }

    /** 从磁盘重新载入审计条目（启动恢复时调用）。 */
    public synchronized void reload() {
        entries.clear();
        if (!Files.exists(file)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                var node = mapper.readTree(line);
                entries.add(new AuditEntry(
                        node.path("ts").asLong(),
                        node.path("actor").asText(null),
                        node.path("action").asText(null),
                        node.path("resource").asText(null),
                        node.path("decision").asText(null),
                        node.path("detail").asText(null)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取审计日志失败: " + file, e);
        }
    }
}
