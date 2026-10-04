package com.github.guoyyit.telemetryprivacypipelinejava.store;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import com.github.guoyyit.telemetryprivacypipelinejava.model.RecoveryReport;
import com.github.guoyyit.telemetryprivacypipelinejava.model.StoreSnapshot;
import com.github.guoyyit.telemetryprivacypipelinejava.model.StoredEvent;

/**
 * 不可变快照 + 原子引用切换的内存视图，状态由 {@link DurableEventLog} 持久化支撑。
 *
 * <p>并发模型：所有写入（批次提交、删除、清除水位推进）在同一把写锁内完成
 * “判定墓碑 -&gt; 追加日志 -&gt; 构造新快照 -&gt; 原子发布”，因此并发写入不可能让已删除内容复活；
 * 读者只取一次 {@code volatile} 快照引用并遍历不可变对象，慢导出不会阻塞写入，
 * 且读到的一定是某个已提交的完整版本，不会出现字段级混合。</p>
 */
@Component
public class EventStoreImpl implements EventStore {

    /** 当前已发布版本（不可变）；volatile 保证读者无需加锁即可安全读取。 */
    private volatile State state = new State(0L, 0L, List.of(), Set.of(), 0L, 0L);

    private final DurableEventLog eventLog;

    public EventStoreImpl(DurableEventLog eventLog) {
        this.eventLog = eventLog;
    }

    private record State(long version, long timestampMs, List<StoredEvent> events,
                         Set<String> deletedUsers, long purgeCutoff, long lastSeq) {
    }

    @Override
    public synchronized RecoveryReport recover() {
        DurableEventLog.Replay replay = eventLog.replay();

        List<StoredEvent> recovered = new ArrayList<>();
        List<String> recoveredBatches = new ArrayList<>();
        // 批次按首次出现顺序重放，事件按 seq 排序，保证恢复后视图确定。
        List<DurableEventLog.ReplayedBatch> ordered = new ArrayList<>(replay.batches());
        ordered.sort(Comparator.comparing(b -> b.events().isEmpty() ? Long.MAX_VALUE
                : b.events().get(0).seq()));
        for (DurableEventLog.ReplayedBatch batch : ordered) {
            for (DurableEventLog.StoredEventPayload payload : batch.events()) {
                // 重放时同样应用删除墓碑与清除水位：已撤回同意/已过期的数据不复活。
                if (replay.deletedUsers().contains(payload.userId())) {
                    continue;
                }
                if (payload.timestampMs() < replay.purgeCutoff()) {
                    continue;
                }
                recovered.add(new StoredEvent(payload.seq(), payload.eventId(), payload.eventType(),
                        payload.userId(), payload.timestampMs(), batch.batchId(),
                        payload.rawFields(), java.util.Collections.unmodifiableMap(
                                new java.util.LinkedHashMap<>(payload.redactedFields()))));
            }
            recoveredBatches.add(batch.batchId());
        }
        recovered.sort(Comparator.comparingLong(StoredEvent::seq));

        // 未完成批次写入回滚清单，使状态可区分、可追溯。
        long now = System.currentTimeMillis();
        for (String rolledBack : replay.rolledBackBatches()) {
            eventLog.recordRollback(rolledBack, now);
        }

        this.state = new State(
                1L, now, List.copyOf(recovered),
                Set.copyOf(replay.deletedUsers()), replay.purgeCutoff(), replay.lastSeq());

        long recoveredDeletions = replay.deletedUsers().size();
        return new RecoveryReport(replay.lastSeq(), recovered.size(), recoveredDeletions,
                List.copyOf(replay.rolledBackBatches()), List.copyOf(recoveredBatches));
    }

    @Override
    public long appendBatch(String batchId, List<StoredEvent> events) {
        synchronized (this) {
            State current = this.state;
            long now = System.currentTimeMillis();

            // 阶段 1：PREPARE。崩溃发生在此后、COMMIT 之前 => 恢复时回滚整个批次。
            eventLog.prepareBatch(batchId, now);

            // 阶段 2：在同一把写锁内分配序号并逐条 fsync 写事件。
            // 墓碑检查放在日志写入前：已删除用户的数据既不进日志也不进快照，杜绝复活。
            long seq = current.lastSeq;
            List<StoredEvent> accepted = new ArrayList<>(events.size());
            for (StoredEvent event : events) {
                if (current.deletedUsers.contains(event.userId())) {
                    continue;
                }
                if (event.timestampMs() < current.purgeCutoff) {
                    continue;
                }
                seq++;
                StoredEvent numbered = new StoredEvent(seq, event.eventId(), event.eventType(),
                        event.userId(), event.timestampMs(), batchId,
                        event.rawFields(), event.redactedFields());
                eventLog.writeEvent(numbered);
                accepted.add(numbered);
            }

            // 阶段 3：COMMIT 并构造、原子发布新版本。快照只在全部事件落盘后才切换。
            eventLog.commitBatch(batchId, now);
            List<StoredEvent> all = new ArrayList<>(current.events);
            all.addAll(accepted);
            all.sort(Comparator.comparingLong(StoredEvent::seq));
            this.state = new State(current.version + 1, now, List.copyOf(all),
                    current.deletedUsers, current.purgeCutoff, seq);
            return this.state.version;
        }
    }

    @Override
    public long deleteUsers(String reason, Set<String> userIds, long nowMs) {
        synchronized (this) {
            State current = this.state;
            Set<String> merged = new LinkedHashSet<>(current.deletedUsers);
            merged.addAll(userIds);
            // 先让墓碑持久化，再发布不含这些用户事件的新版本。
            eventLog.writeDeletion(reason, Set.copyOf(userIds), nowMs);
            List<StoredEvent> remaining = new ArrayList<>();
            for (StoredEvent event : current.events) {
                if (!merged.contains(event.userId())) {
                    remaining.add(event);
                }
            }
            this.state = new State(current.version + 1, nowMs, List.copyOf(remaining),
                    Set.copyOf(merged), current.purgeCutoff, current.lastSeq);
            return this.state.version;
        }
    }

    @Override
    public long advancePurgeCutoff(long cutoff, long nowMs) {
        synchronized (this) {
            State current = this.state;
            if (cutoff <= current.purgeCutoff) {
                return current.version;
            }
            eventLog.writePurge(cutoff, nowMs);
            List<StoredEvent> remaining = new ArrayList<>();
            for (StoredEvent event : current.events) {
                if (event.timestampMs() >= cutoff) {
                    remaining.add(event);
                }
            }
            this.state = new State(current.version + 1, nowMs, List.copyOf(remaining),
                    current.deletedUsers, cutoff, current.lastSeq);
            return this.state.version;
        }
    }

    @Override
    public StoreSnapshot snapshot() {
        State current = this.state;
        return new StoreSnapshot(current.version, current.timestampMs,
                current.events, current.deletedUsers);
    }

    @Override
    public long currentVersion() {
        return state.version;
    }

    @Override
    public boolean isDeleted(String userId) {
        return state.deletedUsers.contains(userId);
    }
}
