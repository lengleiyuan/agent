package cd.lan1akea.core.approval;

import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.util.IdGenerator;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 待审批记录。包含审批决策所需的全部上下文信息。
 */
public class PendingApproval {

    public enum Status { PENDING, APPROVED, DENIED, EXPIRED }

    private final String approvalId;
    private final String sessionId;
    private final String requesterId;
    private final String agentName;
    private final String toolName;
    private final String toolDescription;
    private final Map<String, Object> arguments;
    private final String question;
    private final String riskLevel;
    private final List<Msg> recentMessages;
    private volatile Status status;
    private final long createdAt;
    private final long expiresAt;
    private volatile String approverId;
    private volatile String approverComment;
    private volatile long resolvedAt;

    private PendingApproval(Builder builder) {
        this.approvalId = builder.approvalId != null ? builder.approvalId : IdGenerator.nextIdStr();
        this.sessionId = builder.sessionId;
        this.requesterId = builder.requesterId;
        this.agentName = builder.agentName;
        this.toolName = builder.toolName;
        this.toolDescription = builder.toolDescription;
        this.arguments = builder.arguments != null
            ? Collections.unmodifiableMap(builder.arguments) : Collections.emptyMap();
        this.question = builder.question;
        this.riskLevel = builder.riskLevel != null ? builder.riskLevel : "MEDIUM";
        this.recentMessages = builder.recentMessages != null
            ? List.copyOf(builder.recentMessages) : List.of();
        this.status = builder.status != null ? builder.status : Status.PENDING;
        this.createdAt = builder.createdAt > 0 ? builder.createdAt : System.currentTimeMillis();
        this.expiresAt = builder.expiresAt > 0 ? builder.expiresAt : this.createdAt + 300_000;
    }

    public static Builder builder() { return new Builder(); }

    // ── getters ──
    public String getApprovalId() { return approvalId; }
    public String getSessionId() { return sessionId; }
    public String getRequesterId() { return requesterId; }
    public String getAgentName() { return agentName; }
    public String getToolName() { return toolName; }
    public String getToolDescription() { return toolDescription; }
    public Map<String, Object> getArguments() { return arguments; }
    public String getQuestion() { return question; }
    public String getRiskLevel() { return riskLevel; }
    public List<Msg> getRecentMessages() { return recentMessages; }
    public Status getStatus() { return status; }
    public long getCreatedAt() { return createdAt; }
    public long getExpiresAt() { return expiresAt; }
    public String getApproverId() { return approverId; }
    public String getApproverComment() { return approverComment; }
    public long getResolvedAt() { return resolvedAt; }

    // ── 状态变更（package-private，由 ApprovalStore 调用） ──
    void approve(String approverId, String comment) {
        this.status = Status.APPROVED;
        this.approverId = approverId;
        this.approverComment = comment;
        this.resolvedAt = System.currentTimeMillis();
    }

    void deny(String approverId, String comment) {
        this.status = Status.DENIED;
        this.approverId = approverId;
        this.approverComment = comment;
        this.resolvedAt = System.currentTimeMillis();
    }

    void expire() { this.status = Status.EXPIRED; }

    boolean isExpired() { return System.currentTimeMillis() > expiresAt; }

    public static class Builder {
        private String approvalId;
        private String sessionId;
        private String requesterId;
        private String agentName;
        private String toolName;
        private String toolDescription;
        private Map<String, Object> arguments;
        private String question;
        private String riskLevel;
        private List<Msg> recentMessages;
        private Status status;
        private long createdAt;
        private long expiresAt;

        public Builder approvalId(String v) { this.approvalId = v; return this; }
        public Builder sessionId(String v) { this.sessionId = v; return this; }
        public Builder requesterId(String v) { this.requesterId = v; return this; }
        public Builder agentName(String v) { this.agentName = v; return this; }
        public Builder toolName(String v) { this.toolName = v; return this; }
        public Builder toolDescription(String v) { this.toolDescription = v; return this; }
        public Builder arguments(Map<String, Object> v) { this.arguments = v; return this; }
        public Builder question(String v) { this.question = v; return this; }
        public Builder riskLevel(String v) { this.riskLevel = v; return this; }
        public Builder recentMessages(List<Msg> v) { this.recentMessages = v; return this; }
        public Builder status(Status v) { this.status = v; return this; }
        public Builder createdAt(long v) { this.createdAt = v; return this; }
        public Builder expiresAt(long v) { this.expiresAt = v; return this; }

        public PendingApproval build() { return new PendingApproval(this); }
    }
}
