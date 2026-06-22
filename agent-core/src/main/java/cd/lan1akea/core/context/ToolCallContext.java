package cd.lan1akea.core.context;

/**
 * 工具调用上下文。
 */
public class ToolCallContext extends RuntimeContext {

    private final String toolName;
    private final String toolCallId;
    private final String argumentsJson;

    public ToolCallContext(String tenantId, String userId, String sessionId,
                            String agentName, String toolName, String toolCallId,
                            String argumentsJson) {
        super(tenantId, userId, sessionId, agentName, null);
        this.toolName = toolName;
        this.toolCallId = toolCallId;
        this.argumentsJson = argumentsJson;
    }

    public String getToolName() { return toolName; }
    public String getToolCallId() { return toolCallId; }
    public String getArgumentsJson() { return argumentsJson; }
}
