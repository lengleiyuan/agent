package cd.lan1akea.core.hook;

import cd.lan1akea.core.context.RuntimeContext;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hook 执行上下文。
 * 携带 Agent 执行时的运行时信息（租户、会话、用户、迭代次数等）。
 *
 * <p>推荐使用工厂方法创建：</p>
 * <pre>{@code HookContext.from(runtimeContext, 0);}</pre>
 */
public class HookContext {

    /**
     * Agent 名称
     */
    private final String agentName;

    /**
     * 请求追踪 ID
     */
    private final String requestId;

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
     * 从 RuntimeContext 创建 HookContext（最常用）。
     */
    public static HookContext from(RuntimeContext ctx, int iteration) {
        return new HookContext(ctx.getAgentName(), ctx.getRequestId(),
            ctx.getTenantId(), ctx.getSessionId(),
            ctx.getUserId(), iteration, List.of(), ctx.getAttributes());
    }

    /**
     * 从 RuntimeContext 创建 HookContext，指定迭代次数和已调用工具。
     */
    public static HookContext from(RuntimeContext ctx, int iteration, List<String> calledTools) {
        return new HookContext(ctx.getAgentName(), ctx.getRequestId(),
            ctx.getTenantId(), ctx.getSessionId(),
            ctx.getUserId(), iteration, calledTools, ctx.getAttributes());
    }

    /**
     * 构造 HookContext。
     */
    public HookContext(String agentName, String tenantId, String sessionId,
                       String userId, int currentIteration,
                       List<String> calledTools,
                       Map<String, Object> attributes) {
        this(agentName, null, tenantId, sessionId, userId, currentIteration, calledTools, attributes);
    }

    /**
     * 构造 HookContext（含 requestId）。
     */
    public HookContext(String agentName, String requestId, String tenantId, String sessionId,
                       String userId, int currentIteration,
                       List<String> calledTools,
                       Map<String, Object> attributes) {
        this.agentName = agentName;
        this.requestId = requestId;
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
     * @return 请求追踪ID
     */
    public String getRequestId() { return requestId; }

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
