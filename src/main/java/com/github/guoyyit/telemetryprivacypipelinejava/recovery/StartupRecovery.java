package com.github.guoyyit.telemetryprivacypipelinejava.recovery;

import com.github.guoyyit.telemetryprivacypipelinejava.model.RecoveryReport;
import com.github.guoyyit.telemetryprivacypipelinejava.store.EventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动恢复：在服务对外可用前重放日志。
 *
 * <p>已提交批次被完整重放（不丢不重），PREPARED 但未 COMMIT 的批次被判定为未完成并回滚，
 * 其批次 ID 写入 rollback.log，状态可区分、可追溯，且绝不会留下部分可见写入。</p>
 */
@Component
public class StartupRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupRecovery.class);

    private final EventStore store;
    private volatile RecoveryReport report;

    public StartupRecovery(EventStore store) {
        this.store = store;
    }

    @Override
    public void run(ApplicationArguments args) {
        recover();
    }

    public RecoveryReport recover() {
        RecoveryReport result = store.recover();
        this.report = result;
        log.info("启动恢复完成: 已恢复事件={}, 删除墓碑={}, 已提交批次={}, 回滚未完成批次={}",
                result.recoveredEvents(),
                result.recoveredDeletions(),
                result.recoveredBatches().size(),
                result.rolledBackBatches());
        return result;
    }

    public RecoveryReport report() {
        return report;
    }
}
