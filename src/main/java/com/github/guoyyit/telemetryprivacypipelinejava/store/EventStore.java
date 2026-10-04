package com.github.guoyyit.telemetryprivacypipelinejava.store;

import java.util.List;
import java.util.Set;
import com.github.guoyyit.telemetryprivacypipelinejava.model.RecoveryReport;
import com.github.guoyyit.telemetryprivacypipelinejava.model.StoreSnapshot;
import com.github.guoyyit.telemetryprivacypipelinejava.model.StoredEvent;

/**
 * 多版本追加式存储：写入生成新版本，读取返回不可变快照，慢导出不阻塞写入。
 */
public interface EventStore {

    /** 启动时从持久化日志恢复，已提交批次重放，未完成批次回滚。 */
    RecoveryReport recover();

    /**
     * 以一个可区分的批次原子提交事件；返回提交后的版本号。
     * PREPARED 后崩溃将在恢复时被判定为未完成并回滚，绝不留下部分写入。
     * 命中删除墓碑的用户数据不会被提交，防止已删除内容复活。
     */
    long appendBatch(String batchId, List<StoredEvent> events);

    /** 原子提交一组用户的删除（撤回同意），并使之后并发写入无法让旧内容复活。 */
    long deleteUsers(String reason, Set<String> userIds, long nowMs);

    /** 推进保留期清除水位：timestamp &lt; cutoff 的事件从所有视图中消失。 */
    long advancePurgeCutoff(long cutoff, long nowMs);

    /** 获取某个已提交的完整版本快照（读不加写锁，返回不可变对象）。 */
    StoreSnapshot snapshot();

    long currentVersion();

    /** 判断用户是否已有删除墓碑（撤回同意）。 */
    boolean isDeleted(String userId);
}
