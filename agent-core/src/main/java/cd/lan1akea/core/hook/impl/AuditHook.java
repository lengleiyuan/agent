package cd.lan1akea.core.hook.impl;

import cd.lan1akea.core.hook.*;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 审计 Hook。
 *
 * 记录所有工具调用的审计日志，用于合规审查。
 * 同时监听 PRE_TOOL_CALL 和 POST_TOOL_CALL。
 */
public class AuditHook implements Hook {

    /**
     * Hook 名称
     */
    private final String name;
    /**
     * 审计日志列表
     */
    private final List<AuditEntry> auditLog = new CopyOnWriteArrayList<>();

    /**
     * 创建默认审计 Hook（名称为 AuditHook）。
     */
    public AuditHook() {
        this("AuditHook");
    }

    /**
     * 创建指定名称的审计 Hook。
     */
    public AuditHook(String name) {
        this.name = name;
    }

    /**
     * @return Hook 名称
     */
    @Override
    public String getName() { return name; }

    /**
     * @return PRE_TOOL_CALL 和 POST_TOOL_CALL 事件类型
     */
    @Override
    public Set<HookEventType> getSubscribedEventTypes() {
        return Set.of(HookEventType.PRE_TOOL_CALL, HookEventType.POST_TOOL_CALL);
    }

    /**
     * 记录工具调用的审计日志。
     */
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

    /**
     * @return 审计日志（只读）
     */
    public List<AuditEntry> getAuditLog() {
        return new ArrayList<>(auditLog);
    }

    /**
     * 清空所有审计日志。
     */
    public void clear() { auditLog.clear(); }

    /**
     * 审计条目。
     */
    public static class AuditEntry {
        /**
         * 时间戳
         */
        private final long timestamp;
        /**
         * Agent 名称
         */
        private final String agentName;
        /**
         * 租户 ID
         */
        private final String tenantId;
        /**
         * 用户 ID
         */
        private final String userId;
        /**
         * 事件类型
         */
        private final String eventType;
        /**
         * 审计详情
         */
        private final String detail;

        /**
         * 创建审计条目。
         */
        AuditEntry(long timestamp, String agentName, String tenantId,
                    String userId, String eventType, String detail) {
            this.timestamp = timestamp;
            this.agentName = agentName;
            this.tenantId = tenantId;
            this.userId = userId;
            this.eventType = eventType;
            this.detail = detail;
        }

        /**
         * @return 时间戳
         */
        public long getTimestamp() { return timestamp; }
        /**
         * @return Agent 名称
         */
        public String getAgentName() { return agentName; }
        /**
         * @return 租户 ID
         */
        public String getTenantId() { return tenantId; }
        /**
         * @return 用户 ID
         */
        public String getUserId() { return userId; }
        /**
         * @return 事件类型
         */
        public String getEventType() { return eventType; }
        /**
         * @return 审计详情
         */
        public String getDetail() { return detail; }

        /**
         * @return 格式化审计文本
         */
        @Override
        public String toString() {
            return String.format("[Audit %d] agent=%s tenant=%s user=%s event=%s detail=%s",
                timestamp, agentName, tenantId, userId, eventType, detail);
        }
    }
}
