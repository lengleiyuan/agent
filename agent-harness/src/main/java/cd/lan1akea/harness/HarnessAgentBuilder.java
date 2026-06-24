package cd.lan1akea.harness;

import cd.lan1akea.core.agent.ReActAgent;
import cd.lan1akea.core.agent.config.AgentConfig;
import cd.lan1akea.core.agent.config.AgentExecutionConfig;
import cd.lan1akea.core.exception.AgentConfigurationException;
import cd.lan1akea.core.hook.Hook;
import cd.lan1akea.core.hook.HookChain;
import cd.lan1akea.core.model.ChatModel;
import cd.lan1akea.core.state.AgentStateStore;
import cd.lan1akea.core.tool.ToolRegistry;
import cd.lan1akea.core.workspace.Workspace;
import cd.lan1akea.harness.support.AnnotationToolResolver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * HarnessAgent Builder。
 * <p>
 * 流式 API 构建 Agent 实例。
 * <pre>
 * HarnessAgent agent = HarnessAgent.builder()
 *     .name("MyAgent")
 *     .model(new OpenAIChatModel(apiKey, "gpt-4o"))
 *     .tool(new CalculatorTool())
 *     .hook(new AuditHook())
 *     .build();
 * </pre>
 * </p>
 */
public class HarnessAgentBuilder {

    private String name;
    private ChatModel model;
    private final List<Object> toolObjects = new ArrayList<>();
    private final List<Hook> hooks = new ArrayList<>();
    private AgentStateStore stateStore;
    private Workspace workspace;
    private AgentExecutionConfig executionConfig;

    public HarnessAgentBuilder name(String name) { this.name = name; return this; }
    public HarnessAgentBuilder model(ChatModel model) { this.model = model; return this; }

    public HarnessAgentBuilder tool(Object toolObj) {
        this.toolObjects.add(toolObj);
        return this;
    }

    public HarnessAgentBuilder tools(Object... toolObjects) {
        this.toolObjects.addAll(Arrays.asList(toolObjects));
        return this;
    }

    public HarnessAgentBuilder hook(Hook hook) { this.hooks.add(hook); return this; }

    public HarnessAgentBuilder hooks(Hook... hookList) {
        this.hooks.addAll(Arrays.asList(hookList));
        return this;
    }

    public HarnessAgentBuilder stateStore(AgentStateStore stateStore) {
        this.stateStore = stateStore; return this;
    }

    public HarnessAgentBuilder workspace(Workspace workspace) {
        this.workspace = workspace; return this;
    }

    public HarnessAgentBuilder executionConfig(AgentExecutionConfig config) {
        this.executionConfig = config; return this;
    }

    /**
     * 构建 HarnessAgent。
     *
     * @return HarnessAgent 实例
     * @throws AgentConfigurationException 如果缺少必要配置
     */
    public HarnessAgent build() {
        if (name == null || name.isBlank()) throw new AgentConfigurationException("Agent name 不能为空");
        if (model == null) throw new AgentConfigurationException("ChatModel 不能为 null");

        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.addResolver(new AnnotationToolResolver());
        for (Object obj : toolObjects) toolRegistry.registerTool(obj);

        HookChain hookChain = new HookChain();
        for (Hook hook : hooks) hookChain.register(hook);

        AgentConfig config = AgentConfig.builder()
            .name(name)
            .model(model)
            .toolRegistry(toolRegistry)
            .hookChain(hookChain)
            .stateStore(stateStore)
            .workspace(workspace)
            .executionConfig(executionConfig)
            .build();

        ReActAgent agent = new ReActAgent(config);
        agent.build().block();
        return new HarnessAgent(agent);
    }
}
