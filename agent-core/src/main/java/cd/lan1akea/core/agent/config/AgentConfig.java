package cd.lan1akea.core.agent.config;

import cd.lan1akea.core.hook.AroundHookChain;
import cd.lan1akea.core.hook.HookChain;
import cd.lan1akea.core.intervention.InterventionStore;
import cd.lan1akea.core.agent.loop.SessionGate;
import cd.lan1akea.core.model.ChatModel;
import cd.lan1akea.core.state.AgentStateStore;
import cd.lan1akea.core.tool.ToolRegistry;

/**
 * Agent 配置。
 * Builder 模式构建，包含 Agent 运行所需的全部组件。
 */
public class AgentConfig {

    /**
     * Agent 名称。
     */
    private final String name;
    /**
     * 聊天模型。
     */
    private final ChatModel model;
    /**
     * 工具注册表。
     */
    private final ToolRegistry toolRegistry;
    /**
     * Hook 链。
     */
    private final HookChain hookChain;
    /**
     * AroundHook 链。
     */
    private final AroundHookChain aroundHookChain;
    /**
     * 可选的状态存储。
     */
    private final AgentStateStore stateStore;
    /**
     * 执行配置。
     */
    private final AgentExecutionConfig executionConfig;
    /**
     * 介入存储。
     */
    private final InterventionStore interventionStore;
    /** 会话门控（可选，默认 LocalSessionGate） */
    private final SessionGate sessionGate;

    /**
     * 使用 Builder 构造 AgentConfig。
     *
     * @param builder 配置了字段的 builder
     */
    private AgentConfig(Builder builder) {
        this.name = builder.name;
        this.model = builder.model;
        this.toolRegistry = builder.toolRegistry;
        this.hookChain = builder.hookChain;
        this.aroundHookChain = builder.aroundHookChain;
        this.stateStore = builder.stateStore;
        this.executionConfig = builder.executionConfig != null
            ? builder.executionConfig : AgentExecutionConfig.defaults();
        this.interventionStore = builder.interventionStore;
        this.sessionGate = builder.sessionGate;
    }

    /**
     * @return Agent 名称
     */
    public String getName() { return name; }
    /**
     * @return 聊天模型
     */
    public ChatModel getModel() { return model; }
    /**
     * @return 工具注册表
     */
    public ToolRegistry getToolRegistry() { return toolRegistry; }
    /**
     * @return Hook 链
     */
    public HookChain getHookChain() { return hookChain; }
    /**
     * @return AroundHook 链
     */
    public AroundHookChain getAroundHookChain() { return aroundHookChain; }
    /**
     * @return 状态存储
     */
    public AgentStateStore getStateStore() { return stateStore; }
    /**
     * @return 执行配置
     */
    public AgentExecutionConfig getExecutionConfig() { return executionConfig; }
    /**
     * @return 介入存储
     */
    public InterventionStore getInterventionStore() { return interventionStore; }
    /** @return 会话门控 */
    public SessionGate getSessionGate() { return sessionGate; }

    /**
     * 创建 Builder。
     *
     * @return 新的 Builder
     */
    public static Builder builder() { return new Builder(); }

    /**
     * AgentConfig 建造者。
     */
    public static class Builder {
        private String name;
        private ChatModel model;
        private ToolRegistry toolRegistry;
        private HookChain hookChain;
        private AroundHookChain aroundHookChain;
        private AgentStateStore stateStore;
        /** 执行配置。 */
        private AgentExecutionConfig executionConfig;
        /** 介入存储。 */
        private InterventionStore interventionStore;
        /** 会话门控（可选，默认 LocalSessionGate） */
        private SessionGate sessionGate;

        /**
         * 设置 Agent 名称。
         */
        public Builder name(String name) { this.name = name; return this; }
        /**
         * 设置聊天模型。
         */
        public Builder model(ChatModel model) { this.model = model; return this; }
        /**
         * 设置工具注册表。
         */
        public Builder toolRegistry(ToolRegistry toolRegistry) { this.toolRegistry = toolRegistry; return this; }
        /**
         * 设置 Hook 链。
         */
        public Builder hookChain(HookChain hookChain) { this.hookChain = hookChain; return this; }
        /**
         * 设置 AroundHook 链。
         */
        public Builder aroundHookChain(AroundHookChain aroundHookChain) { this.aroundHookChain = aroundHookChain; return this; }
        /**
         * 设置状态存储。
         */
        public Builder stateStore(AgentStateStore stateStore) { this.stateStore = stateStore; return this; }
        /**
         * 设置执行配置。
         */
        public Builder executionConfig(AgentExecutionConfig executionConfig) { this.executionConfig = executionConfig; return this; }
        public Builder interventionStore(InterventionStore v) { this.interventionStore = v; return this; }
        /** 设置会话门控（可选，默认 LocalSessionGate） */
        public Builder sessionGate(SessionGate v) { this.sessionGate = v; return this; }

        /**
         * 构建 AgentConfig。
         *
         * @return 新的 AgentConfig
         * @throws IllegalStateException 如果名称或模型为 null/blank
         */
        public AgentConfig build() {
            if (name == null || name.isBlank()) throw new IllegalStateException("Agent name 不能为空");
            if (model == null) throw new IllegalStateException("ChatModel 不能为 null");
            return new AgentConfig(this);
        }
    }
}
