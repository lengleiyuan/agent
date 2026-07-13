package cd.lan1akea.core.agent;

import cd.lan1akea.core.agent.config.AgentConfig;
import cd.lan1akea.core.agent.loop.LoopExecutor;
import cd.lan1akea.core.agent.loop.LocalSessionGate;
import cd.lan1akea.core.agent.loop.ModelCallPipeline;
import cd.lan1akea.core.agent.loop.RequestPipeline;
import cd.lan1akea.core.agent.loop.SessionGate;
import cd.lan1akea.core.agent.loop.ToolCallOrchestrator;
import cd.lan1akea.core.hook.AroundHookChain;
import cd.lan1akea.core.hook.HookChain;
import cd.lan1akea.core.hook.HookPipeline;
import cd.lan1akea.core.hook.impl.AgentMetricsHook;
import cd.lan1akea.core.hook.impl.StreamTokenEstimationHook;
import cd.lan1akea.core.intervention.InMemoryInterventionStore;
import cd.lan1akea.core.agent.loop.InterventionResolver;
import cd.lan1akea.core.intervention.InterventionStore;
import cd.lan1akea.core.metrics.AgentMetrics;
import cd.lan1akea.core.model.ChatModel;
import cd.lan1akea.core.model.ModelContextWindow;
import cd.lan1akea.core.state.AgentStateStore;
import cd.lan1akea.core.tool.ToolExecutor;
import cd.lan1akea.core.tool.ToolRegistry;
import cd.lan1akea.core.util.IdGenerator;

/**
 * ReActAgent 组件装配器。
 *
 * <p>收拢 ReActAgent 内部全部组件的构建与连线逻辑，保持构造函数简洁。
 * 调用方只需一行 {@code AssembledComponents comps = ReActAgentComponentAssembler.assemble(config)}。
 */
final class ReActAssembler {

    private ReActAssembler() {}

    /**
     * 装配 ReActAgent 所需的全部内部组件。
     *
     * @param config Agent 配置
     * @return 装配完成的组件集合
     */
    static AssembledComponents assemble(AgentConfig config) {
        String id = IdGenerator.nextIdStr();

        ChatModel model = config.getModel();
        ToolRegistry toolRegistry = config.getToolRegistry() != null
                ? config.getToolRegistry() : new ToolRegistry();
        ToolExecutor toolExecutor = new ToolExecutor(toolRegistry);

        AgentStateStore stateStore = config.getStateStore();
        int maxInput = model.getMaxInputTokens();
        ModelContextWindow contextWindow = new ModelContextWindow(
                model.getModelName(), maxInput, maxInput / 2);

        // Hook 链装配
        HookChain hookChain = config.getHookChain() != null
                ? config.getHookChain() : new HookChain();
        hookChain.register(new AgentMetricsHook("AgentMetrics", AgentMetrics.NOOP,
                model.getModelName(), model.getProvider()));

        AroundHookChain aroundChain = config.getAroundHookChain() != null
                ? config.getAroundHookChain() : new AroundHookChain();
        aroundChain.register(new AgentMetricsHook("AgentMetrics", AgentMetrics.NOOP,
                model.getModelName(), model.getProvider()));
        aroundChain.register(new StreamTokenEstimationHook(contextWindow.getEstimator()));
        HookPipeline hookPipeline = new HookPipeline(hookChain, aroundChain);

        // 核心管线装配
        ToolCallOrchestrator toolOrch = new ToolCallOrchestrator(
                toolExecutor, hookPipeline);
        ModelCallPipeline modelPipeline = new ModelCallPipeline(
                model, hookPipeline, toolRegistry);

        InterventionStore interventionStore = config.getInterventionStore() != null
                ? config.getInterventionStore() : new InMemoryInterventionStore();
        InterventionResolver interventionResolver = new InterventionResolver(
                interventionStore, toolOrch);

        LoopExecutor loopExecutor = new LoopExecutor(
                modelPipeline, toolOrch, hookPipeline, interventionResolver);

        SessionGate sessionGate = config.getSessionGate() != null
                ? config.getSessionGate() : new LocalSessionGate();
        RequestPipeline pipeline = new RequestPipeline(
                loopExecutor, stateStore, hookPipeline,
                config.getExecutionConfig(), config.getName(), null,
                interventionStore, sessionGate);

        return new AssembledComponents(id, pipeline, hookPipeline, stateStore, contextWindow);
    }

    record AssembledComponents(
            String id,
            RequestPipeline pipeline,
            HookPipeline hookPipeline,
            AgentStateStore stateStore,
            ModelContextWindow contextWindow) {}
}
