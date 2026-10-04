package com.github.guoyyit.telemetryprivacypipelinejava.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.telemetryprivacypipelinejava.config.PipelineProperties;
import com.github.guoyyit.telemetryprivacypipelinejava.model.StoredEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 基于分段追加日志的持久化。
 *
 * <ul>
 *   <li>{@code events.log}：{@code P(prepare)}、{@code E(event)}、{@code C(commit)} 三种行记录；
 *       只有读到 COMMIT 的批次才算已提交，未见 COMMIT 的 PREPARED 批次在恢复时判定为未完成并回滚。</li>
 *   <li>{@code deletions.log}：{@code D(user deletion)}（墓碑）与 {@code X(expiry purge)}（保留期清除水位）。</li>
 *   <li>{@code rollback.log}：恢复时被判定未完成而回滚的批次清单，使批次状态可区分、可追溯。</li>
 * </ul>
 *
 * <p>每条记录写入后执行 {@code fsync}，崩溃只会丢失尚未 COMMIT 的批次，
 * 已提交数据不丢不重，也不会留下部分可见的写入。</p>
 */
@Component
public class DurableEventLog {

    public static final String EVENTS_FILE = "events.log";
    public static final String DELETIONS_FILE = "deletions.log";
    public static final String ROLLBACK_FILE = "rollback.log";

    private final Path dir;
    private final ObjectMapper mapper = new ObjectMapper();

    public DurableEventLog(PipelineProperties properties) {
        this.dir = Path.of(properties.getDataDir());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建数据目录: " + dir, e);
        }
    }

    /** 追加一条 PREPARE 记录。 */
    public synchronized void prepareBatch(String batchId, long nowMs) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("t", "P");
        rec.put("batch", batchId);
        rec.put("ts", nowMs);
        append(dir.resolve(EVENTS_FILE), rec);
    }

    /** 追加一条事件记录（属于某个已 PREPARE 的批次）。 */
    public synchronized void writeEvent(StoredEvent event) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("t", "E");
        rec.put("batch", event.batchId());
        rec.put("seq", event.seq());
        rec.put("eventId", event.eventId());
        rec.put("eventType", event.eventType());
        rec.put("userId", event.userId());
        rec.put("ts", event.timestampMs());
        rec.put("raw", event.rawFields());
        rec.put("red", event.redactedFields());
        append(dir.resolve(EVENTS_FILE), rec);
    }

    /** 追加 COMMIT 记录；此记录 fsync 成功后批次才成为已提交版本。 */
    public synchronized void commitBatch(String batchId, long nowMs) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("t", "C");
        rec.put("batch", batchId);
        rec.put("ts", nowMs);
        append(dir.resolve(EVENTS_FILE), rec);
    }

    /** 将恢复时判定为未完成的批次写入回滚清单（可追溯）。 */
    public synchronized void recordRollback(String batchId, long nowMs) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("batch", batchId);
        rec.put("ts", nowMs);
        append(dir.resolve(ROLLBACK_FILE), rec);
    }

    /** 追加一组用户的删除墓碑（撤回同意）。 */
    public synchronized void writeDeletion(String reason, Set<String> userIds, long nowMs) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("t", "D");
        rec.put("reason", reason);
        rec.put("ts", nowMs);
        rec.put("users", new ArrayList<>(userIds));
        append(dir.resolve(DELETIONS_FILE), rec);
    }

    /** 追加一次保留期清除水位（清除 timestamp &lt; cutoff 的事件）。 */
    public synchronized void writePurge(long cutoff, long nowMs) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("t", "X");
        rec.put("cutoff", cutoff);
        rec.put("ts", nowMs);
        append(dir.resolve(DELETIONS_FILE), rec);
    }

    /** 从头重放全部日志，重建提交/未完成批次、删除墓碑与清除水位。 */
    public synchronized Replay replay() {
        // batchId -> 有序事件；仅保留事件行，是否已提交由 committed 集合决定
        Map<String, List<StoredEventPayload>> prepared = new LinkedHashMap<>();
        Set<String> committed = new LinkedHashSet<>();
        long maxSeq = 0L;

        Path eventsPath = dir.resolve(EVENTS_FILE);
        if (Files.exists(eventsPath)) {
            try (BufferedReader reader = Files.newBufferedReader(eventsPath, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    var node = mapper.readTree(line);
                    String type = node.path("t").asText();
                    String batch = node.path("batch").asText();
                    switch (type) {
                        case "P" -> prepared.computeIfAbsent(batch, k -> new ArrayList<>());
                        case "E" -> {
                            prepared.computeIfAbsent(batch, k -> new ArrayList<>())
                                    .add(readPayload(node, batch));
                            maxSeq = Math.max(maxSeq, node.path("seq").asLong());
                        }
                        case "C" -> {
                            prepared.computeIfAbsent(batch, k -> new ArrayList<>());
                            committed.add(batch);
                        }
                        default -> { /* 忽略未知记录，保持前向兼容 */ }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("重放事件日志失败: " + eventsPath, e);
            }
        }

        List<ReplayedBatch> batches = new ArrayList<>();
        List<String> rolledBack = new ArrayList<>();
        for (Map.Entry<String, List<StoredEventPayload>> entry : prepared.entrySet()) {
            if (committed.contains(entry.getKey())) {
                batches.add(new ReplayedBatch(entry.getKey(), List.copyOf(entry.getValue())));
            } else {
                rolledBack.add(entry.getKey());
            }
        }

        Set<String> deletedUsers = new LinkedHashSet<>();
        long purgeCutoff = 0L;
        List<DeletionRecord> deletions = new ArrayList<>();
        Path deletionsPath = dir.resolve(DELETIONS_FILE);
        if (Files.exists(deletionsPath)) {
            try (BufferedReader reader = Files.newBufferedReader(deletionsPath, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    var node = mapper.readTree(line);
                    if ("D".equals(node.path("t").asText())) {
                        Set<String> users = new LinkedHashSet<>();
                        node.path("users").forEach(u -> users.add(u.asText()));
                        deletedUsers.addAll(users);
                        deletions.add(new DeletionRecord(
                                node.path("reason").asText(),
                                Set.copyOf(users),
                                node.path("ts").asLong()));
                    } else if ("X".equals(node.path("t").asText())) {
                        purgeCutoff = Math.max(purgeCutoff, node.path("cutoff").asLong());
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("重放删除日志失败: " + deletionsPath, e);
            }
        }

        return new Replay(List.copyOf(batches), List.copyOf(rolledBack), Set.copyOf(deletedUsers),
                purgeCutoff, maxSeq, List.copyOf(deletions));
    }

    private StoredEventPayload readPayload(com.fasterxml.jackson.databind.JsonNode node, String batch) {
        return new StoredEventPayload(
                node.path("seq").asLong(),
                node.path("eventId").asText(null),
                node.path("eventType").asText(null),
                node.path("userId").asText(null),
                node.path("ts").asLong(),
                batch,
                toStringMap(node.path("raw")),
                toStringMap(node.path("red")));
    }

    private Map<String, String> toStringMap(com.fasterxml.jackson.databind.JsonNode node) {
        // LinkedHashMap 保留 JSON 行中的字段顺序（脱敏字段按字典序写入）。
        Map<String, String> map = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().asText()));
        return java.util.Collections.unmodifiableMap(map);
    }

    private void append(Path file, Object record) {
        try {
            byte[] bytes = (mapper.writeValueAsString(record) + System.lineSeparator())
                    .getBytes(StandardCharsets.UTF_8);
            // 以同步方式打开：写入后 fsync，保证记录在返回前已持久化。
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(file.toFile(), true)) {
                out.write(bytes);
                out.getFD().sync();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("写入持久化日志失败: " + file, e);
        }
    }

    /** 一条已持久化的事件载荷。 */
    public record StoredEventPayload(
            long seq, String eventId, String eventType, String userId,
            long timestampMs, String batchId,
            Map<String, String> rawFields,
            Map<String, String> redactedFields) {

        StoredEvent toStoredEvent() {
            // 用 LinkedHashMap 复制以保留字段（脱敏后为字典序）的确定迭代顺序。
            return new StoredEvent(seq, eventId, eventType, userId, timestampMs, batchId,
                    Map.copyOf(rawFields), java.util.Collections.unmodifiableMap(
                            new LinkedHashMap<>(redactedFields)));
        }
    }

    /** 一个已提交批次的重放视图。 */
    public record ReplayedBatch(String batchId, List<StoredEventPayload> events) {
    }

    /** 一次用户删除记录。 */
    public record DeletionRecord(String reason, Set<String> userIds, long timestampMs) {
    }

    /** 全量重放结果。 */
    public static final class Replay {
        private final List<ReplayedBatch> batches;
        private final List<String> rolledBackBatches;
        private final Set<String> deletedUsers;
        private final long purgeCutoff;
        private final long lastSeq;
        private final List<DeletionRecord> deletions;

        public Replay(List<ReplayedBatch> batches, List<String> rolledBackBatches,
                      Set<String> deletedUsers, long purgeCutoff, long lastSeq,
                      List<DeletionRecord> deletions) {
            this.batches = batches;
            this.rolledBackBatches = rolledBackBatches;
            this.deletedUsers = deletedUsers;
            this.purgeCutoff = purgeCutoff;
            this.lastSeq = lastSeq;
            this.deletions = deletions;
        }

        public List<ReplayedBatch> batches() { return batches; }
        public List<String> rolledBackBatches() { return rolledBackBatches; }
        public Set<String> deletedUsers() { return deletedUsers; }
        public long purgeCutoff() { return purgeCutoff; }
        public long lastSeq() { return lastSeq; }
        public List<DeletionRecord> deletions() { return deletions; }
    }
}
