package com.github.guoyyit.telemetryprivacypipelinejava.export;

import com.github.guoyyit.telemetryprivacypipelinejava.audit.AuditLog;
import com.github.guoyyit.telemetryprivacypipelinejava.model.AuditEntry;
import com.github.guoyyit.telemetryprivacypipelinejava.model.StoreSnapshot;
import com.github.guoyyit.telemetryprivacypipelinejava.model.StoredEvent;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 最小化视图投影。
 *
 * <ul>
 *   <li>默认（或 {@code raw=false}）所有调用方都只能看到脱敏视图；</li>
 *   <li>仅 {@code PRIVACY_ADMIN} 角色在显式请求 {@code raw=true} 时可看原始值；</li>
 *   <li>越权请求抛出 {@link AccessDeniedException}，且无论是否抛异常都先写审计留痕。</li>
 * </ul>
 */
@Component
public class ViewProjector {

    private final AuditLog auditLog;

    public ViewProjector(AuditLog auditLog) {
        this.auditLog = auditLog;
    }

    /**
     * 将快照中的事件投影为调用方可见的视图。
     *
     * @param snap         已提交的完整快照
     * @param caller       调用方身份
     * @param rawRequested 是否请求未脱敏原始值
     */
    public List<StoredEvent> project(StoreSnapshot snap, Caller caller, boolean rawRequested) {
        boolean allowRaw = rawRequested && caller.isPrivacyAdmin();
        if (rawRequested && !caller.isPrivacyAdmin()) {
            // 留痕先于抛出，保证越权行为可追溯。
            auditLog.record(new AuditEntry(
                    System.currentTimeMillis(),
                    caller.id(),
                    "RAW_VIEW_DENIED",
                    "events",
                    "DENY",
                    "角色 " + caller.role() + " 未授权查看原始字段"));
            throw new AccessDeniedException(caller, "events",
                    "调用方未被授权查看未脱敏原始值");
        }
        if (rawRequested) {
            auditLog.record(new AuditEntry(
                    System.currentTimeMillis(),
                    caller.id(),
                    "RAW_VIEW_GRANTED",
                    "events",
                    "ALLOW",
                    "授权查看原始字段，事件数=" + snap.events().size()));
        }

        List<StoredEvent> projected = new ArrayList<>(snap.events().size());
        for (StoredEvent event : snap.events()) {
            if (allowRaw) {
                projected.add(event);
            } else {
                // userId 属于用户标识，最小化视图中一并去除，仅保留不可反推的脱敏字段。
                projected.add(new StoredEvent(event.seq(), event.eventId(), event.eventType(),
                        null, event.timestampMs(), event.batchId(),
                        event.redactedFields(), event.redactedFields()));
            }
        }
        return projected;
    }
}
