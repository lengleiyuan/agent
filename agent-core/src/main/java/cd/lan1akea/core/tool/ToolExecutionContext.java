package cd.lan1akea.core.tool;

/**
 * 工具执行上下文。
 * <p>
 * 携带工具执行时的环境信息（租户、用户、会话等）。
 * </p>
 */
public class ToolExecutionContext {

    private final String tenantId;
    private final String userId;
    private final String sessionId;
    private final String agentName;

    public ToolExecutionContext(String tenantId, String userId,
                                 String sessionId, String agentName) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.sessionId = sessionId;
        this.agentName = agentName;
    }

    public String getTenantId() { return tenantId; }
    public String getUserId() { return userId; }
    public String getSessionId() { return sessionId; }
    public String getAgentName() { return agentName; }
}
