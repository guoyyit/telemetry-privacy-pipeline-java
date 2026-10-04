package com.github.guoyyit.telemetryprivacypipelinejava.model;

import java.util.List;

/**
 * 启动恢复报告：已重放的提交批次与被判定未完成而回滚的批次可区分。
 */
public record RecoveryReport(
        long lastSeq,
        long recoveredEvents,
        long recoveredDeletions,
        List<String> rolledBackBatches,
        List<String> recoveredBatches) {
}
