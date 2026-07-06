package cd.lan1akea.core.tool;

import cd.lan1akea.core.context.RuntimeContext;

import java.util.Map;

/**
 * 工具调用上下文。
 * 分层设计，便于扩展：
 * ToolCallContext
 * ├── callId / toolName           调用标识
 * ├── arguments: ToolArguments    LLM 参数（get/getString/getNumber）
 * └── identity: CallerIdentity    调用者身份（tenant/user/session/attrs）
 *
 * 创建方式：
 * 从 RuntimeContext（推荐）：
 * ToolCallContext ctx = ToolCallContext.builder()
 *     .callId("call_1").toolName("search")
 *     .arguments(ToolArguments.fromJson("{\"query\":\"hello\"}"))
 *     .identity(CallerIdentity.from(runtimeContext))
 *     .build();
 *
 * 最简（无身份信息）：
 * ToolCallContext ctx = ToolCallContext.of("call_1", "search",
 *     ToolArguments.fromJson("{\"query\":\"hello\"}"));
 */
public class ToolCallContext {

    /**
     * 唯一工具调用标识
     */
    private final String callId;
    /**
     * 工具名称
     */
    private final String toolName;
    /**
     * LLM 传入的工具参数
     */
    private final ToolArguments arguments;
    /**
     * 调用者身份信息
     */
    private final CallerIdentity identity;
    /**
     * 审批预检通过标记。框架在介入恢复重试时设为 true，工具据此跳过审批检查。
     */
    private volatile boolean approved;

    private ToolCallContext(Builder builder) {
        this.callId = builder.callId;
        this.toolName = builder.toolName;
        this.arguments = builder.arguments != null ? builder.arguments : new ToolArguments(null);
        this.identity = builder.identity != null ? builder.identity : CallerIdentity.from(null);
    }

    /**
     * 使用给定参数创建 ToolCallContext。
     *
     * @param callId    调用 ID
     * @param toolName  工具名称
     * @param arguments 工具参数
     * @return 新的 ToolCallContext
     */
    public static ToolCallContext of(String callId, String toolName, ToolArguments arguments) {
        return builder().callId(callId).toolName(toolName).arguments(arguments).build();
    }

    /**
     * 使用 Map 参数创建 ToolCallContext，无身份信息。
     *
     * @param callId    调用 ID
     * @param toolName  工具名称
     * @param arguments 参数 Map
     * @return 新的 ToolCallContext
     */
    public static ToolCallContext of(String callId, String toolName, Map<String, Object> arguments) {
        return builder().callId(callId).toolName(toolName).arguments(arguments).build();
    }

    /**
     * 创建新的 Builder。
     *
     * @return 新的 Builder
     */
    public static Builder builder() { return new Builder(); }


    /**
     * @return 调用 ID
     */
    public String getCallId() { return callId; }
    /**
     * @return 工具名称
     */
    public String getToolName() { return toolName; }
    /**
     * @return LLM 传入的参数
     */
    public ToolArguments getArguments() { return arguments; }
    /**
     * @return 调用者身份
     */
    public CallerIdentity getIdentity() { return identity; }

    /**
     * 代理 ToolArguments.get(String)
     */
    public <T> T get(String name) { return arguments.get(name); }
    /**
     * 代理 ToolArguments.getString(String)
     */
    public String getString(String name) { return arguments.getString(name); }
    /**
     * 代理 ToolArguments.getNumber(String)
     */
    public Number getNumber(String name) { return arguments.getNumber(name); }
    /**
     * 代理 ToolArguments.getBoolean(String)
     */
    public Boolean getBoolean(String name) { return arguments.getBoolean(name); }
    /**
     * 代理 CallerIdentity.getTenantId()
     */
    public String getTenantId() { return identity.getTenantId(); }
    /**
     * 代理 CallerIdentity.getUserId()
     */
    public String getUserId() { return identity.getUserId(); }
    /**
     * 代理 CallerIdentity.getSessionId()
     */
    public String getSessionId() { return identity.getSessionId(); }
    /**
     * 代理 CallerIdentity.getAgentName()
     */
    public String getAgentName() { return identity.getAgentName(); }
    /**
     * 代理 CallerIdentity.getAttributes()
     */
    public Map<String, Object> getAttributes() { return identity.getAttributes(); }
    /**
     * 代理 CallerIdentity.getAttribute(String)
     */
    public <T> T getAttribute(String key) { return identity.getAttribute(key); }

    /**
     * 审批预检是否已通过。
     */
    public boolean isApproved() { return approved; }
    /**
     * 设置审批预检通过标记（框架内部使用）。
     */
    public void setApproved(boolean v) { this.approved = v; }
    /**
     * 代理 ToolArguments.asMap()
     */
    public Map<String, Object> getArgumentsMap() { return arguments.asMap(); }
    /**
     * 代理 ToolArguments.toJson()
     */
    public String getArgumentsJson() { return arguments.toJson(); }

    @Override
    public String toString() {
        return "ToolCallContext{callId=" + callId + ", tool=" + toolName
            + ", " + identity + ", args=" + arguments + "}";
    }

    public static class Builder {
        private String callId;
        private String toolName;
        private ToolArguments arguments;
        private CallerIdentity identity;

        public Builder callId(String v) { this.callId = v; return this; }
        public Builder toolName(String v) { this.toolName = v; return this; }

        /**
         * LLM 参数（Map 形式，内部转为 ToolArguments）。
         */
        public Builder arguments(Map<String, Object> v) {
            this.arguments = new ToolArguments(v); return this;
        }

        /**
         * LLM 参数（直接传入 ToolArguments）。
         */
        public Builder arguments(ToolArguments v) { this.arguments = v; return this; }

        /**
         * LLM 参数（JSON 字符串形式）。
         */
        public Builder argumentsJson(String json) {
            this.arguments = ToolArguments.fromJson(json); return this;
        }

        /**
         * 调用者身份。
         */
        public Builder identity(CallerIdentity v) { this.identity = v; return this; }

        /**
         * 从 RuntimeContext 提取身份信息并注入到子实体。
         */
        public Builder from(RuntimeContext ctx) {
            this.identity = CallerIdentity.from(ctx);
            return this;
        }

        // 便捷方法 —— 直接设置身份字段（内部创建 CallerIdentity）
        public Builder tenantId(String v) {
            if (identity == null) identity = CallerIdentity.builder().build();
            identity = CallerIdentity.builder()
                .tenantId(v).userId(identity.getUserId())
                .sessionId(identity.getSessionId()).agentName(identity.getAgentName())
                .attributes(identity.getAttributes()).build();
            return this;
        }

        public Builder userId(String v) {
            if (identity == null) identity = CallerIdentity.builder().build();
            identity = CallerIdentity.builder()
                .tenantId(identity.getTenantId()).userId(v)
                .sessionId(identity.getSessionId()).agentName(identity.getAgentName())
                .attributes(identity.getAttributes()).build();
            return this;
        }

        public Builder attributes(Map<String, Object> v) {
            if (identity == null) identity = CallerIdentity.builder().build();
            identity = CallerIdentity.builder()
                .tenantId(identity.getTenantId()).userId(identity.getUserId())
                .sessionId(identity.getSessionId()).agentName(identity.getAgentName())
                .attributes(v).build();
            return this;
        }

        public Builder sessionId(String v) {
            if (identity == null) identity = CallerIdentity.builder().build();
            identity = CallerIdentity.builder()
                .tenantId(identity.getTenantId()).userId(identity.getUserId())
                .sessionId(v).agentName(identity.getAgentName())
                .attributes(identity.getAttributes()).build();
            return this;
        }

        /**
         * 构建 ToolCallContext。
         *
         * @return 新的 ToolCallContext
         */
        public ToolCallContext build() {
            return new ToolCallContext(this);
        }
    }
}
