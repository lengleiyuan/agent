package cd.lan1akea.core.agent.config;

import cd.lan1akea.core.hook.HookChain;
import cd.lan1akea.core.model.ChatModel;
import cd.lan1akea.core.state.AgentStateStore;
import cd.lan1akea.core.tool.ToolRegistry;
import cd.lan1akea.core.workspace.Workspace;

/**
 * Agent 配置。
 * <p>
 * Builder 模式构建，包含 Agent 运行所需的全部组件。
 * </p>
 */
public class AgentConfig {

    private final String name;
    private final ChatModel model;
    private final ToolRegistry toolRegistry;
    private final HookChain hookChain;
    private final AgentStateStore stateStore;
    private final Workspace workspace;
    private final AgentExecutionConfig executionConfig;

    private AgentConfig(Builder builder) {
        this.name = builder.name;
        this.model = builder.model;
        this.toolRegistry = builder.toolRegistry;
        this.hookChain = builder.hookChain;
        this.stateStore = builder.stateStore;
        this.workspace = builder.workspace;
        this.executionConfig = builder.executionConfig != null
            ? builder.executionConfig : AgentExecutionConfig.defaults();
    }

    public String getName() { return name; }
    public ChatModel getModel() { return model; }
    public ToolRegistry getToolRegistry() { return toolRegistry; }
    public HookChain getHookChain() { return hookChain; }
    public AgentStateStore getStateStore() { return stateStore; }
    public Workspace getWorkspace() { return workspace; }
    public AgentExecutionConfig getExecutionConfig() { return executionConfig; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String name;
        private ChatModel model;
        private ToolRegistry toolRegistry;
        private HookChain hookChain;
        private AgentStateStore stateStore;
        private Workspace workspace;
        private AgentExecutionConfig executionConfig;

        public Builder name(String name) { this.name = name; return this; }
        public Builder model(ChatModel model) { this.model = model; return this; }
        public Builder toolRegistry(ToolRegistry toolRegistry) { this.toolRegistry = toolRegistry; return this; }
        public Builder hookChain(HookChain hookChain) { this.hookChain = hookChain; return this; }
        public Builder stateStore(AgentStateStore stateStore) { this.stateStore = stateStore; return this; }
        public Builder workspace(Workspace workspace) { this.workspace = workspace; return this; }
        public Builder executionConfig(AgentExecutionConfig executionConfig) { this.executionConfig = executionConfig; return this; }

        public AgentConfig build() {
            if (name == null || name.isBlank()) throw new IllegalStateException("Agent name 不能为空");
            if (model == null) throw new IllegalStateException("ChatModel 不能为 null");
            return new AgentConfig(this);
        }
    }
}
