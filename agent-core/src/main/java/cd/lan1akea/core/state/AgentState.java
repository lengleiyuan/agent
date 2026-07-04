package cd.lan1akea.core.state;

import cd.lan1akea.core.message.Msg;

import java.util.List;
import java.util.Map;

/**
 * Agent 状态快照。
 * 可序列化的 Agent 执行状态，承载完整的执行上下文。
 * 用于暂停/恢复、崩溃恢复以及跨进程状态迁移。
 */
public class AgentState {

    /**
     * Agent 名称
     */
    private String agentName;
    /**
     * 会话标识
     */
    private String sessionId;
    /**
     * 当前迭代计数
     */
    private int iteration;
    /**
     * 消息历史（含结构化内容块）
     */
    private List<Msg> messages;
    /**
     * 工具执行上下文
     */
    private Map<String, Object> toolContext;
    /**
     * 累计 token 消耗
     */
    private long totalTokens;
    /**
     * 是否被关闭中断
     */
    private boolean shutdownInterrupted;
    /**
     * 计划模式状态（通过 Hook 系统使用）
     */
    private String planState;
    /**
     * 待解决的介入 ID（null=无）
     */
    private String pendingInterventionId;
    /**
     * 介入类型（APPROVAL/CLARIFY/PAUSE）
     */
    private String interventionType;
    /**
     * 暂停时快照的工具参数 JSON
     */
    private String pausedToolArgsJson;
    /**
     * 状态快照时间戳
     */
    private long timestamp;

    /**
     * 无参构造（JSON 反序列化用）。
     */
    public AgentState() {}

    /**
     * 创建完整 Agent 状态。
     *
     * @param agentName           Agent 名称
     * @param sessionId           会话标识
     * @param iteration           当前迭代计数
     * @param messages            消息历史
     * @param toolContext         工具执行上下文
     * @param totalTokens         累计 token 消耗
     * @param shutdownInterrupted 是否被关闭中断
     * @param planState           计划模式状态
     * @param timestamp           状态快照时间戳
     */
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


    /**
     * @return Agent 名称
     */
    public String getAgentName() { return agentName; }
    /**
     * 设置 Agent 名称。
     */
    public void setAgentName(String agentName) { this.agentName = agentName; }

    /**
     * @return 会话标识
     */
    public String getSessionId() { return sessionId; }
    /**
     * 设置会话标识。
     */
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    /**
     * @return 当前迭代计数
     */
    public int getIteration() { return iteration; }
    /**
     * 设置当前迭代计数。
     */
    public void setIteration(int iteration) { this.iteration = iteration; }

    /**
     * @return 消息历史
     */
    public List<Msg> getMessages() { return messages; }
    /**
     * 设置消息历史。
     */
    public void setMessages(List<Msg> messages) { this.messages = messages; }

    /**
     * @return 工具执行上下文
     */
    public Map<String, Object> getToolContext() { return toolContext; }
    /**
     * 设置工具执行上下文。
     */
    public void setToolContext(Map<String, Object> toolContext) { this.toolContext = toolContext; }

    /**
     * @return 累计 token 消耗
     */
    public long getTotalTokens() { return totalTokens; }
    /**
     * 设置累计 token 消耗。
     */
    public void setTotalTokens(long totalTokens) { this.totalTokens = totalTokens; }

    /**
     * @return 是否被关闭中断
     */
    public boolean isShutdownInterrupted() { return shutdownInterrupted; }
    /**
     * 设置是否被关闭中断。
     */
    public void setShutdownInterrupted(boolean shutdownInterrupted) { this.shutdownInterrupted = shutdownInterrupted; }

    /**
     * @return 计划模式状态
     */
    public String getPlanState() { return planState; }
    /**
     * 设置计划模式状态。
     */
    public void setPlanState(String planState) { this.planState = planState; }

    public String getPendingInterventionId() { return pendingInterventionId; }
    public void setPendingInterventionId(String v) { this.pendingInterventionId = v; }
    public String getInterventionType() { return interventionType; }
    public void setInterventionType(String v) { this.interventionType = v; }
    public String getPausedToolArgsJson() { return pausedToolArgsJson; }
    public void setPausedToolArgsJson(String v) { this.pausedToolArgsJson = v; }

    /**
     * @return 状态快照时间戳
     */
    public long getTimestamp() { return timestamp; }
    /**
     * 设置状态快照时间戳。
     */
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
