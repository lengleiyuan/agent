package cd.lan1akea.core.hook;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hook 执行上下文。
 * 携带 Agent 执行时的运行时信息（租户、会话、用户、迭代次数等）。
 */
public class HookContext {

    /**
     * Agent 名称
     */
    private final String agentName;

    /**
     * 租户 ID
     */
    private final String tenantId;

    /**
     * 会话 ID（可能为 null）
     */
    private final String sessionId;

    /**
     * 用户 ID
     */
    private final String userId;

    /**
     * 当前 ReAct 迭代
     */
    private final int currentIteration;

    /**
     * 已调用工具列表
     */
    private final List<String> calledTools;

    /**
     * 扩展属性
     */
    private final Map<String, Object> attributes;

    /**
     * 构造 HookContext。
     */
    public HookContext(String agentName, String tenantId, String sessionId,
                       String userId, int currentIteration,
                       List<String> calledTools,
                       Map<String, Object> attributes) {
        this.agentName = agentName;
        this.tenantId = tenantId;
        this.sessionId = sessionId;
        this.userId = userId;
        this.currentIteration = currentIteration;
        this.calledTools = calledTools != null
            ? Collections.unmodifiableList(calledTools)
            : Collections.emptyList();
        this.attributes = attributes != null
            ? Collections.unmodifiableMap(new HashMap<>(attributes))
            : Collections.emptyMap();
    }

    /**
     * @return Agent名称
     */
    public String getAgentName() { return agentName; }

    /**
     * @return 租户ID
     */
    public String getTenantId() { return tenantId; }

    /**
     * @return 会话ID
     */
    public String getSessionId() { return sessionId; }

    /**
     * @return 用户ID
     */
    public String getUserId() { return userId; }

    /**
     * @return 当前迭代
     */
    public int getCurrentIteration() { return currentIteration; }

    /**
     * @return 已调用工具列表
     */
    public java.util.List<String> getCalledTools() { return calledTools; }

    /**
     * @return 扩展属性，按 key 取值
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }
}
