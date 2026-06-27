package cd.lan1akea.core.tool;

/**
 * 工具执行上下文。
 * 携带工具执行时的环境信息（租户、用户、会话等）。
 */
public class ToolExecutionContext {

    /**
     * 租户 ID
     */
    private final String tenantId;
    /**
     * 用户 ID
     */
    private final String userId;
    /**
     * 会话 ID
     */
    private final String sessionId;
    /**
     * Agent 名称
     */
    private final String agentName;

    /**
     * 创建工具执行上下文。
     *
     * @param tenantId  租户 ID
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     * @param agentName Agent 名称
     */
    public ToolExecutionContext(String tenantId, String userId,
                                 String sessionId, String agentName) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.sessionId = sessionId;
        this.agentName = agentName;
    }

    /**
     * @return 租户 ID
     */
    public String getTenantId() { return tenantId; }
    /**
     * @return 用户 ID
     */
    public String getUserId() { return userId; }
    /**
     * @return 会话 ID
     */
    public String getSessionId() { return sessionId; }
    /**
     * @return Agent 名称
     */
    public String getAgentName() { return agentName; }
}
