package cd.lan1akea.core.agent;

import cd.lan1akea.core.agent.config.AgentConfig;
import cd.lan1akea.core.agent.loop.*;
import cd.lan1akea.core.CoreConstants.UI;
import cd.lan1akea.core.context.RuntimeContext;
import cd.lan1akea.core.exception.AgentConfigurationException;
import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.hook.recorder.HookRecorder;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.metrics.AgentMetrics;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.state.AgentStateStore;
import cd.lan1akea.core.tool.*;
import cd.lan1akea.core.util.IdGenerator;
import cd.lan1akea.core.util.ValidationUtils;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * ReActAgent - 多租户并发安全的 Agent 实现。
 * 薄门面，请求处理全部委托给 RequestPipeline + LoopExecutor。
 */
public class ReActAgent implements StreamableAgent, CallableAgent {

    final String id;
    final String name;
    final AgentConfig config;
    final LoopExecutor loopExecutor;
    final RequestPipeline pipeline;
    final HookChain hookChain;
    final HookDispatcher hookDispatcher;
    final AroundHookChain aroundHookChain;

    AgentStateStore stateStore;
    ModelContextWindow contextWindow;
    HookRecorder hookRecorder;
    AgentMetrics metrics = AgentMetrics.NOOP;
    String systemMessage;
    private volatile boolean built;

    public ReActAgent(AgentConfig config) {
        ValidationUtils.notNull(config, "AgentConfig");
        ValidationUtils.notNull(config.getModel(), "ChatModel");

        this.config = config;
        this.id = IdGenerator.nextIdStr();
        this.name = config.getName();

        ChatModel model = config.getModel();
        ToolRegistry toolRegistry = config.getToolRegistry() != null
                ? config.getToolRegistry() : new ToolRegistry();
        ToolExecutor toolExecutor = new ToolExecutor(toolRegistry);

        this.hookChain = config.getHookChain() != null
                ? config.getHookChain() : new HookChain();
        this.hookDispatcher = new HookDispatcher(this.hookChain);
        this.aroundHookChain = config.getAroundHookChain() != null
                ? config.getAroundHookChain() : new AroundHookChain();

        this.stateStore = config.getStateStore();
        int maxInput = model.getMaxInputTokens();
        this.contextWindow = new ModelContextWindow(model.getModelName(), maxInput, maxInput / 2);

        // 组装内部组件
        LoopDecisionEngine engine = new LoopDecisionEngine();
        ToolCallOrchestrator toolOrch = new ToolCallOrchestrator(
                toolExecutor, toolRegistry, hookDispatcher, aroundHookChain);
        ModelCallPipeline modelPipeline = new ModelCallPipeline(
                model, hookDispatcher, toolRegistry, aroundHookChain, metrics);
        cd.lan1akea.core.intervention.InterventionStore interventionStore =
                config.getInterventionStore() != null
                        ? config.getInterventionStore()
                        : new cd.lan1akea.core.intervention.InMemoryInterventionStore();
        this.loopExecutor = new LoopExecutor(
                engine, modelPipeline, toolOrch, hookDispatcher, metrics, interventionStore);
        this.pipeline = new RequestPipeline(
                loopExecutor, stateStore, aroundHookChain,
                config.getExecutionConfig(), name, systemMessage);
    }

    @Override
    public Mono<ChatResponse> chat(List<Msg> messages) {
        ensureBuilt();
        return pipeline.execute(messages, null);
    }

    @Override
    public Mono<ChatResponse> chat(List<Msg> messages, RuntimeContext ctx) {
        ensureBuilt();
        return pipeline.execute(messages, ctx);
    }

    @Override
    public Flux<ChatStreamChunk> stream(List<Msg> messages) {
        ensureBuilt();
        return pipeline.executeStream(messages, null);
    }

    @Override
    public Flux<ChatStreamChunk> stream(List<Msg> messages,
                                         RuntimeContext ctx) {
        ensureBuilt();
        return pipeline.executeStream(messages, ctx);
    }

    @Override
    public Mono<ChatResponse> chat(List<Msg> messages, Class<?> outputClass) {
        return chat(StructuredOutputReminder.injectSchemaInstruction(messages, outputClass));
    }

    @Override
    public Flux<ChatStreamChunk> stream(List<Msg> messages, Class<?> outputClass) {
        return stream(StructuredOutputReminder.injectSchemaInstruction(messages, outputClass));
    }

    @Override
    public void interrupt() {
        pipeline.interrupt();
    }

    @Override
    public void interrupt(Msg feedbackMsg) {
        for (LoopContext ctx : pipeline.getActiveRequests().values()) {
            ctx.interrupt(feedbackMsg);
        }
    }

    public void interruptBySession(String sessionId) {
        pipeline.interruptBySession(sessionId);
    }

    public final Mono<Void> build() {
        if (built) {
            return Mono.error(new AgentConfigurationException(
                    UI.AGENT_PREFIX + name + UI.AGENT_ALREADY_BUILT));
        }
        built = true;
        return doBuild();
    }

    protected Mono<Void> doBuild() { return Mono.empty(); }

    public Mono<Void> shutdown() {
        built = false;
        pipeline.shutdown();
        return Mono.empty();
    }

    private void ensureBuilt() {
        if (!built) throw new AgentConfigurationException(
                UI.AGENT_PREFIX + name + UI.AGENT_NOT_BUILT);
    }

    @Override public String getName() { return name; }
    @Override public String getId() { return id; }
    public AgentConfig getConfig() { return config; }
    public ChatModel getModel() { return config.getModel(); }
    public ToolRegistry getToolRegistry() { return config.getToolRegistry(); }
    public HookChain getHookChain() { return hookChain; }
    public AgentStateStore getStateStore() { return stateStore; }
    public ModelContextWindow getContextWindow() { return contextWindow; }
    public HookRecorder getHookRecorder() { return hookRecorder; }
    public AgentMetrics getMetrics() { return metrics; }
    public boolean isBuilt() { return built; }
    public boolean isRunning() { return pipeline.isRunning(); }

    public void setStateStore(AgentStateStore v) { this.stateStore = v; }
    public void setSystemMessage(String msg) { this.systemMessage = msg; }
    public void setHookRecorder(HookRecorder v) {
        this.hookRecorder = v;
        if (this.hookDispatcher != null) this.hookDispatcher.setRecorder(v);
    }
    public void setMetrics(AgentMetrics v) { this.metrics = v != null ? v : AgentMetrics.NOOP; }
}
