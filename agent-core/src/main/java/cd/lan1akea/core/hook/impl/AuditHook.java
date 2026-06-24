package cd.lan1akea.core.hook.impl;

import cd.lan1akea.core.hook.*;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 审计 Hook。
 * <p>
 * 记录所有工具调用的审计日志，用于合规审查。
 * 同时监听 PRE_TOOL_CALL 和 POST_TOOL_CALL。
 * </p>
 */
public class AuditHook implements Hook {

    private final String name;
    private final List<AuditEntry> auditLog = new ArrayList<>();

    public AuditHook() {
        this("AuditHook");
    }

    public AuditHook(String name) {
        this.name = name;
    }

    @Override
    public String getName() { return name; }

    @Override
    public Set<HookEventType> getSubscribedEventTypes() {
        return Set.of(HookEventType.PRE_TOOL_CALL, HookEventType.POST_TOOL_CALL);
    }

    @Override
    public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
        String eventType = event.getEventType();
        auditLog.add(new AuditEntry(
            Instant.now().toEpochMilli(),
            context != null ? context.getAgentName() : "?",
            context != null ? context.getTenantId() : null,
            context != null ? context.getUserId() : null,
            eventType,
            "hook audit"
        ));
        return Mono.just(HookResult.continue_());
    }

    /** @return 审计日志（只读） */
    public List<AuditEntry> getAuditLog() {
        return new ArrayList<>(auditLog);
    }

    /** 清空审计日志 */
    public void clear() { auditLog.clear(); }

    /** 审计条目 */
    public static class AuditEntry {
        private final long timestamp;
        private final String agentName;
        private final String tenantId;
        private final String userId;
        private final String eventType;
        private final String detail;

        AuditEntry(long timestamp, String agentName, String tenantId,
                    String userId, String eventType, String detail) {
            this.timestamp = timestamp;
            this.agentName = agentName;
            this.tenantId = tenantId;
            this.userId = userId;
            this.eventType = eventType;
            this.detail = detail;
        }

        public long getTimestamp() { return timestamp; }
        public String getAgentName() { return agentName; }
        public String getTenantId() { return tenantId; }
        public String getUserId() { return userId; }
        public String getEventType() { return eventType; }
        public String getDetail() { return detail; }

        @Override
        public String toString() {
            return String.format("[Audit %d] agent=%s tenant=%s user=%s event=%s detail=%s",
                timestamp, agentName, tenantId, userId, eventType, detail);
        }
    }
}
