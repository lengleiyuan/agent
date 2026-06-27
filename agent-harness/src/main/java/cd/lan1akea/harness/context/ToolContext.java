package cd.lan1akea.harness.context;

import cd.lan1akea.core.tool.ToolCallContext;

import java.util.Map;

/**
 * 工具调用上下文（门面层）。
 * 业务代码在 @ToolFunction 方法中注入此对象，
 * 获取 LLM 参数和调用者身份。内部委托到 core 层的 ToolCallContext。
 *
 * 示例：
 *     @ToolFunction(name = "delete_user", permission = "user:delete")
 *     public ToolResult deleteUser(@ToolParam("userId") String userId,
 *                                   ToolContext ctx) {
 *         String tenant = ctx.getTenantId();
 *         String query = ctx.getString("keyword");
 *     }
 */
public class ToolContext {

    /**
     * core 层委托对象。
     */
    private final ToolCallContext delegate;

    /**
     * 基于 core 层 ToolCallContext 构造门面 ToolContext。
     */
    public ToolContext(ToolCallContext delegate) {
        this.delegate = delegate;
    }

    /**
     * 内部使用：获取 core 层委托。
     */
    ToolCallContext delegate() { return delegate; }

    // ========================================================================
    // 调用标识
    // ========================================================================

    /**
     * 返回调用 ID。
     */
    public String getCallId() { return delegate.getCallId(); }
    /**
     * 返回工具名称。
     */
    public String getToolName() { return delegate.getToolName(); }

    // ========================================================================
    // LLM 参数（委托到 ToolArguments）
    // ========================================================================

    /**
     * 根据名称获取参数值。
     */
    public <T> T get(String name) { return delegate.get(name); }
    /**
     * 获取字符串类型参数。
     */
    public String getString(String name) { return delegate.getString(name); }
    /**
     * 获取数字类型参数。
     */
    public Number getNumber(String name) { return delegate.getNumber(name); }
    /**
     * 获取布尔类型参数。
     */
    public Boolean getBoolean(String name) { return delegate.getBoolean(name); }
    /**
     * 获取全部参数映射。
     */
    public Map<String, Object> getArgumentsMap() { return delegate.getArgumentsMap(); }

    // ========================================================================
    // 调用者身份（委托到 CallerIdentity）
    // ========================================================================

    /**
     * 返回租户 ID。
     */
    public String getTenantId() { return delegate.getTenantId(); }
    /**
     * 返回用户 ID。
     */
    public String getUserId() { return delegate.getUserId(); }
    /**
     * 返回会话 ID。
     */
    public String getSessionId() { return delegate.getSessionId(); }
    /**
     * 返回 Agent 名称。
     */
    public String getAgentName() { return delegate.getAgentName(); }
    /**
     * 返回调用者属性映射。
     */
    public Map<String, Object> getAttributes() { return delegate.getAttributes(); }
    /**
     * 根据键获取调用者属性。
     */
    public <T> T getAttribute(String key) { return delegate.getAttribute(key); }

    /**
     * 返回字符串表示，委托给 core 层。
     */
    @Override
    public String toString() { return delegate.toString(); }
}
