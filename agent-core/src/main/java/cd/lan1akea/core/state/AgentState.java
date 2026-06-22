package cd.lan1akea.core.state;

/**
 * Agent 状态快照。
 * <p>
 * 可序列化的 Agent 执行状态，用于暂停/恢复和崩溃恢复。
 * </p>
 */
public class AgentState {

    private final String agentName;
    private final String sessionId;
    private final int iteration;
    private final String messagesJson;
    private final String toolStateJson;
    private final long timestamp;

    public AgentState(String agentName, String sessionId, int iteration,
                       String messagesJson, String toolStateJson, long timestamp) {
        this.agentName = agentName;
        this.sessionId = sessionId;
        this.iteration = iteration;
        this.messagesJson = messagesJson;
        this.toolStateJson = toolStateJson;
        this.timestamp = timestamp;
    }

    public String getAgentName() { return agentName; }
    public String getSessionId() { return sessionId; }
    public int getIteration() { return iteration; }
    public String getMessagesJson() { return messagesJson; }
    public String getToolStateJson() { return toolStateJson; }
    public long getTimestamp() { return timestamp; }
}
