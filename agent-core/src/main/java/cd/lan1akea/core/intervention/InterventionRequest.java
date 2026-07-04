package cd.lan1akea.core.intervention;

import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.util.IdGenerator;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 人工介入请求。持久化模型，记录暂停时的完整上下文。
 */
public class InterventionRequest {

    public enum Type { TOOL_APPROVAL, TOOL_CLARIFY, BUSINESS_PAUSE }
    public enum Status { PENDING, APPROVED, DENIED, CLARIFIED, EXPIRED }

    private final String interventionId;
    private final String sessionId;
    private final String requestId;
    private final String tenantId;
    private final Type type;
    private volatile Status status;
    private final String agentName;
    private final String toolName;
    private final String question;
    private final String riskLevel;
    private final Map<String, Object> toolArgs;
    private final List<Msg> recentMessages;
    private volatile String resolverId;
    private volatile String resolution;
    private volatile Map<String, Object> modifiedArgs;
    private final Instant createdAt;
    private volatile Instant resolvedAt;
    private final Instant expiresAt;

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
        this.riskLevel = builder.riskLevel != null ? builder.riskLevel : "MEDIUM";
        this.toolArgs = builder.toolArgs != null
                ? Collections.unmodifiableMap(builder.toolArgs) : Collections.emptyMap();
        this.recentMessages = builder.recentMessages != null
                ? List.copyOf(builder.recentMessages) : List.of();
        this.createdAt = Instant.now();
        int ttl = builder.ttlMinutes > 0 ? builder.ttlMinutes : 5;
        this.expiresAt = createdAt.plus(ttl, ChronoUnit.MINUTES);
    }

    public void approve(String resolverId, String resolution) {
        this.status = Status.APPROVED;
        this.resolverId = resolverId;
        this.resolution = resolution;
        this.resolvedAt = Instant.now();
    }

    public void deny(String resolverId, String resolution) {
        this.status = Status.DENIED;
        this.resolverId = resolverId;
        this.resolution = resolution;
        this.resolvedAt = Instant.now();
    }

    public void clarify(String resolverId, String resolution, Map<String, Object> modifiedArgs) {
        this.status = Status.CLARIFIED;
        this.resolverId = resolverId;
        this.resolution = resolution;
        this.modifiedArgs = modifiedArgs != null
                ? Collections.unmodifiableMap(modifiedArgs) : Collections.emptyMap();
        this.resolvedAt = Instant.now();
    }

    public void expire() {
        this.status = Status.EXPIRED;
        this.resolvedAt = Instant.now();
    }

    public boolean isExpired() { return Instant.now().isAfter(expiresAt); }

    // ---- getters ----
    public String getInterventionId() { return interventionId; }
    public String getSessionId() { return sessionId; }
    public String getRequestId() { return requestId; }
    public String getTenantId() { return tenantId; }
    public Type getType() { return type; }
    public Status getStatus() { return status; }
    public String getAgentName() { return agentName; }
    public String getToolName() { return toolName; }
    public String getQuestion() { return question; }
    public String getRiskLevel() { return riskLevel; }
    public Map<String, Object> getToolArgs() { return toolArgs; }
    public List<Msg> getRecentMessages() { return recentMessages; }
    public String getResolverId() { return resolverId; }
    public String getResolution() { return resolution; }
    public Map<String, Object> getModifiedArgs() { return modifiedArgs; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public Instant getExpiresAt() { return expiresAt; }

    public static Builder builder() { return new Builder(); }

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
        private int ttlMinutes = 5;

        public Builder interventionId(String v) { this.interventionId = v; return this; }
        public Builder sessionId(String v) { this.sessionId = v; return this; }
        public Builder requestId(String v) { this.requestId = v; return this; }
        public Builder tenantId(String v) { this.tenantId = v; return this; }
        public Builder type(Type v) { this.type = v; return this; }
        public Builder agentName(String v) { this.agentName = v; return this; }
        public Builder toolName(String v) { this.toolName = v; return this; }
        public Builder question(String v) { this.question = v; return this; }
        public Builder riskLevel(String v) { this.riskLevel = v; return this; }
        public Builder toolArgs(Map<String, Object> v) { this.toolArgs = v; return this; }
        public Builder recentMessages(List<Msg> v) { this.recentMessages = v; return this; }
        public Builder ttlMinutes(int v) { this.ttlMinutes = v; return this; }
        public InterventionRequest build() { return new InterventionRequest(this); }
    }
}
