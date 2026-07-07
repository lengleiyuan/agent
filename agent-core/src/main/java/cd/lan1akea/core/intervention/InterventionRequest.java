package cd.lan1akea.core.intervention;

import cd.lan1akea.core.CoreConstants;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.util.IdGenerator;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 人工介入请求。持久化模型，记录暂停时的完整上下文。
 *
 * <p>当工具调用需要人工审批、参数澄清或业务流程需要暂停时，
 * 创建此请求并持久化。人工解决后（approve/deny/clarify）恢复循环执行。
 */
public class InterventionRequest {

    /**
     * 介入类型枚举。
     */
    public enum Type {
        /** 工具调用需要人工审批 */
        TOOL_APPROVAL,
        /** 工具参数需要人工澄清/修正 */
        TOOL_CLARIFY
    }

    /**
     * 介入状态枚举。
     */
    public enum Status {
        /** 待处理 */
        PENDING,
        /** 已批准 */
        APPROVED,
        /** 已拒绝 */
        DENIED,
        /** 已澄清 */
        CLARIFIED,
        /** 已过期 */
    EXPIRED
    }

    /** 介入记录唯一 ID */
    private final String interventionId;
    /** 所属会话 ID */
    private final String sessionId;
    /** 原始请求追踪 ID */
    private final String requestId;
    /** 租户标识 */
    private final String tenantId;
    /** 介入类型 */
    private final Type type;
    /** 当前处理状态 */
    private volatile Status status;
    /** Agent 名称 */
    private final String agentName;
    /** 被暂停的工具名称 */
    private final String toolName;
    /** 暂停原因/审批问题 */
    private final String question;
    /** 风险等级 */
    private final String riskLevel;
    /** 工具调用参数快照 */
    private final Map<String, Object> toolArgs;
    /** 暂停时的最近消息列表 */
    private final List<Msg> recentMessages;
    /** 处理人 ID */
    private volatile String resolverId;
    /** 处理意见 */
    private volatile String resolution;
    /** 澄清后的修正参数 */
    private volatile Map<String, Object> modifiedArgs;
    /** 创建时间 */
    private final Instant createdAt;
    /** 处理时间 */
    private volatile Instant resolvedAt;
    /** 过期时间 */
    private final Instant expiresAt;

    /**
     * 通过 Builder 构造介入请求。
     *
     * @param builder 配置了字段的 Builder
     */
    private InterventionRequest(Builder builder) {
        this.interventionId = builder.interventionId != null
                ? builder.interventionId : IdGenerator.nextIdStr();
        this.sessionId = builder.sessionId;
        this.requestId = builder.requestId;
        this.tenantId = builder.tenantId;
        this.type = builder.type;
        this.status = Status.PENDING;
        this.agentName = builder.agentName;
        this.toolName = builder.toolName;
        this.question = builder.question;
        this.riskLevel = builder.riskLevel != null ? builder.riskLevel : CoreConstants.Intervention.DEFAULT_RISK_LEVEL;
        this.toolArgs = builder.toolArgs != null
                ? Collections.unmodifiableMap(builder.toolArgs) : Collections.emptyMap();
        this.recentMessages = builder.recentMessages != null
                ? List.copyOf(builder.recentMessages) : List.of();
        this.createdAt = Instant.now();
        int ttl = builder.ttlMinutes >= 0 ? builder.ttlMinutes : CoreConstants.Intervention.DEFAULT_TTL_MINUTES;
        this.expiresAt = createdAt.plus(ttl, ChronoUnit.MINUTES);
    }

    /**
     * 批准此介入请求。
     *
     * @param resolverId 处理人 ID
     * @param resolution 处理意见
     */
    public void approve(String resolverId, String resolution) {
        this.status = Status.APPROVED;
        this.resolverId = resolverId;
        this.resolution = resolution;
        this.resolvedAt = Instant.now();
    }

    /**
     * 拒绝此介入请求。
     *
     * @param resolverId 处理人 ID
     * @param resolution 处理意见
     */
    public void deny(String resolverId, String resolution) {
        this.status = Status.DENIED;
        this.resolverId = resolverId;
        this.resolution = resolution;
        this.resolvedAt = Instant.now();
    }

    /**
     * 澄清此介入请求（带修正参数）。
     *
     * @param resolverId   处理人 ID
     * @param resolution   处理意见
     * @param modifiedArgs 修正后的工具参数
     */
    public void clarify(String resolverId, String resolution, Map<String, Object> modifiedArgs) {
        this.status = Status.CLARIFIED;
        this.resolverId = resolverId;
        this.resolution = resolution;
        this.modifiedArgs = modifiedArgs != null
                ? Collections.unmodifiableMap(modifiedArgs) : Collections.emptyMap();
        this.resolvedAt = Instant.now();
    }

    /**
     * 标记此请求已过期。
     */
    public void expire() {
        this.status = Status.EXPIRED;
        this.resolvedAt = Instant.now();
    }

    /**
     * 判断是否已过期（根据当前时间与 expiresAt 比较）。
     *
     * @return true 表示已过期
     */
    public boolean isExpired() { return Instant.now().isAfter(expiresAt); }

    // ---- getters ----

    /** @return 介入记录唯一 ID */
    public String getInterventionId() { return interventionId; }
    /** @return 所属会话 ID */
    public String getSessionId() { return sessionId; }
    /** @return 原始请求追踪 ID */
    public String getRequestId() { return requestId; }
    /** @return 租户标识 */
    public String getTenantId() { return tenantId; }
    /** @return 介入类型 */
    public Type getType() { return type; }
    /** @return 当前处理状态 */
    public Status getStatus() { return status; }
    /** @return Agent 名称 */
    public String getAgentName() { return agentName; }
    /** @return 被暂停的工具名称 */
    public String getToolName() { return toolName; }
    /** @return 暂停原因/审批问题 */
    public String getQuestion() { return question; }
    /** @return 风险等级 */
    public String getRiskLevel() { return riskLevel; }
    /** @return 工具调用参数快照 */
    public Map<String, Object> getToolArgs() { return toolArgs; }
    /** @return 暂停时的最近消息列表 */
    public List<Msg> getRecentMessages() { return recentMessages; }
    /** @return 处理人 ID */
    public String getResolverId() { return resolverId; }
    /** @return 处理意见 */
    public String getResolution() { return resolution; }
    /** @return 澄清后的修正参数 */
    public Map<String, Object> getModifiedArgs() { return modifiedArgs; }
    /** @return 创建时间 */
    public Instant getCreatedAt() { return createdAt; }
    /** @return 处理时间 */
    public Instant getResolvedAt() { return resolvedAt; }
    /** @return 过期时间 */
    public Instant getExpiresAt() { return expiresAt; }

    /**
     * 创建 InterventionRequest Builder。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() { return new Builder(); }

    /**
     * InterventionRequest 建造者。
     */
    public static class Builder {
        private String interventionId;
        private String sessionId;
        private String requestId;
        private String tenantId;
        private Type type;
        private String agentName;
        private String toolName;
        private String question;
        private String riskLevel;
        private Map<String, Object> toolArgs;
        private List<Msg> recentMessages;
        private int ttlMinutes = CoreConstants.Intervention.DEFAULT_TTL_MINUTES;

        /** 设置介入记录 ID（可选，默认自动生成） */
        public Builder interventionId(String v) { this.interventionId = v; return this; }
        /** 设置会话 ID */
        public Builder sessionId(String v) { this.sessionId = v; return this; }
        /** 设置请求追踪 ID */
        public Builder requestId(String v) { this.requestId = v; return this; }
        /** 设置租户 ID */
        public Builder tenantId(String v) { this.tenantId = v; return this; }
        /** 设置介入类型 */
        public Builder type(Type v) { this.type = v; return this; }
        /** 设置 Agent 名称 */
        public Builder agentName(String v) { this.agentName = v; return this; }
        /** 设置工具名称 */
        public Builder toolName(String v) { this.toolName = v; return this; }
        /** 设置审批问题 */
        public Builder question(String v) { this.question = v; return this; }
        /** 设置风险等级（可选，默认 MEDIUM） */
        public Builder riskLevel(String v) { this.riskLevel = v; return this; }
        /** 设置工具参数 */
        public Builder toolArgs(Map<String, Object> v) { this.toolArgs = v; return this; }
        /** 设置最近消息列表 */
        public Builder recentMessages(List<Msg> v) { this.recentMessages = v; return this; }
        /** 设置 TTL 分钟数（可选，默认 {@value CoreConstants.Intervention#DEFAULT_TTL_MINUTES}） */
        public Builder ttlMinutes(int v) { this.ttlMinutes = v; return this; }

        /**
         * 构建 InterventionRequest。
         *
         * @return 新的 InterventionRequest 实例
         */
        public InterventionRequest build() { return new InterventionRequest(this); }
    }
}
