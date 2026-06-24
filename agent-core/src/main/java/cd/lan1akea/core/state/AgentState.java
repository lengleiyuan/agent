package cd.lan1akea.core.state;

import cd.lan1akea.core.message.Msg;

import java.util.List;
import java.util.Map;

/**
 * Agent 状态快照。
 * <p>
 * 可序列化的 Agent 执行状态，承载完整的执行上下文。
 * 用于暂停/恢复、崩溃恢复以及跨进程状态迁移。
 * </p>
 */
public class AgentState {

    private String agentName;
    private String sessionId;
    private int iteration;
    /** 消息历史（结构化，含 ContentBlock） */
    private List<Msg> messages;
    /** 工具执行上下文（已激活工具组等） */
    private Map<String, Object> toolContext;
    /** 累计 Token 消耗 */
    private long totalTokens;
    /** 是否因进程终止而中断（崩溃恢复标志） */
    private boolean shutdownInterrupted;
    /** 计划模式状态（通过 Hook 体系使用） */
    private String planState;
    private long timestamp;

    /** 无参构造（JSON 反序列化需要） */
    public AgentState() {}

    public AgentState(String agentName, String sessionId, int iteration,
                       List<Msg> messages, Map<String, Object> toolContext,
                       long totalTokens, boolean shutdownInterrupted, String planState,
                       long timestamp) {
        this.agentName = agentName;
        this.sessionId = sessionId;
        this.iteration = iteration;
        this.messages = messages;
        this.toolContext = toolContext;
        this.totalTokens = totalTokens;
        this.shutdownInterrupted = shutdownInterrupted;
        this.planState = planState;
        this.timestamp = timestamp;
    }

    // === Getters / Setters（fastjson2 序列化需要） ===

    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public int getIteration() { return iteration; }
    public void setIteration(int iteration) { this.iteration = iteration; }

    public List<Msg> getMessages() { return messages; }
    public void setMessages(List<Msg> messages) { this.messages = messages; }

    public Map<String, Object> getToolContext() { return toolContext; }
    public void setToolContext(Map<String, Object> toolContext) { this.toolContext = toolContext; }

    public long getTotalTokens() { return totalTokens; }
    public void setTotalTokens(long totalTokens) { this.totalTokens = totalTokens; }

    public boolean isShutdownInterrupted() { return shutdownInterrupted; }
    public void setShutdownInterrupted(boolean shutdownInterrupted) { this.shutdownInterrupted = shutdownInterrupted; }

    public String getPlanState() { return planState; }
    public void setPlanState(String planState) { this.planState = planState; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
