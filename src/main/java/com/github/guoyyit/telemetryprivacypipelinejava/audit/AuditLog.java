package com.github.guoyyit.telemetryprivacypipelinejava.audit;

import java.util.List;
import com.github.guoyyit.telemetryprivacypipelinejava.model.AuditEntry;

/**
 * 合规审计日志：拒绝采集、越权访问等安全决策全部留痕且可追溯。
 */
public interface AuditLog {

    void record(AuditEntry entry);

    List<AuditEntry> entries();
}
