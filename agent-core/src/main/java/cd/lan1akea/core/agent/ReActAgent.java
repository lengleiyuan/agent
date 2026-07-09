package cd.lan1akea.core.agent;

import cd.lan1akea.core.agent.config.AgentConfig;
import cd.lan1akea.core.agent.loop.*;
import cd.lan1akea.core.CoreConstants;
import cd.lan1akea.core.CoreConstants.UI;
import cd.lan1akea.core.context.RuntimeContext;
import cd.lan1akea.core.exception.AgentConfigurationException;
import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.hook.recorder.HookRecorder;
import cd.lan1akea.core.intervention.InMemoryInterventionStore;
import cd.lan1akea.core.intervention.InterventionStore;
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
 * ReActAgent —— 多租户并发安全的 Agent 实现。
 *
 * <p>薄门面模式：构建时注入模型、工具、Hook，运行时将所有请求委托给
 * RequestPipeline + LoopExecutor 处理。无实例级锁，多请求可并发执行。
 * 租户/用户/会话上下文通过 RuntimeContext 显式传递。
 *
 * <p>使用方式：
 * <pre>{@code
 * AgentConfig config = AgentConfig.builder()
 *     .name("my-agent").model(model)
 *     .interventionStore(new InMemoryInterventionStore())
 *     .build();
 * ReActAgent agent = new ReActAgent(config);
 * agent.build().block();
 * ChatResponse resp = agent.chat(List.of(UserMessage.of("hello"))).block();
 * }</pre>
 */
public class ReActAgent implements StreamableAgent, CallableAgent {

    /** Agent 唯一标识 */
    final String id;
    /** Agent 名称 */
    final String name;
    /** Agent 配置 */
    final AgentConfig config;
    /** 请求管线，封装预处理流水线 */
    final RequestPipeline pipeline;
    /** Hook 分发器 */
    final HookDispatcher hookDispatcher;

    /** 状态存储（可选） */
    final AgentStateStore stateStore;
    /** 上下文窗口配置 */
    final ModelContextWindow contextWindow;
    /** Hook 记录器（可选，用于审计/回放） */
    HookRecorder hookRecorder;
    /** 指标收集器（可选，默认 NOOP） */
    AgentMetrics metrics = AgentMetrics.NOOP;
    /** 系统提示消息 */
    String systemMessage;
    /** 是否已通过 build() 初始化 */
    private volatile boolean built;

    /**
     * 使用指定配置创建 ReActAgent。
     *
     * <p>构造函数中组装全部内部组件：LoopDecisionEngine、ToolCallOrchestrator、
     * ModelCallPipeline、LoopExecutor、RequestPipeline。InterventionStore 若未配置则默认内存实现。
     *
     * @param config Agent 配置，必须包含 ChatModel
     */
    public ReActAgent(AgentConfig config) {
        ValidationUtils.notNull(config, CoreConstants.Validation.PARAM_AGENT_CONFIG);
        ValidationUtils.notNull(config.getModel(), CoreConstants.Validation.PARAM_CHAT_MODEL);

        this.config = config;
        this.id = IdGenerator.nextIdStr();
        this.name = config.getName();

        ChatModel model = config.getModel();
        ToolRegistry toolRegistry = config.getToolRegistry() != null
                ? config.getToolRegistry() : new ToolRegistry();
        ToolExecutor toolExecutor = new ToolExecutor(toolRegistry);

        HookChain hookChain = config.getHookChain() != null
                ? config.getHookChain() : new HookChain();
        this.hookDispatcher = new HookDispatcher(hookChain);
        AroundHookChain aroundHookChain = config.getAroundHookChain() != null
                ? config.getAroundHookChain() : new AroundHookChain();

        this.stateStore = config.getStateStore();
        int maxInput = model.getMaxInputTokens();
        this.contextWindow = new ModelContextWindow(model.getModelName(), maxInput, maxInput / 2);

        LoopDecisionEngine engine = new LoopDecisionEngine();
        ToolCallOrchestrator toolOrch = new ToolCallOrchestrator(
                toolExecutor, toolRegistry, hookDispatcher, aroundHookChain);
        ModelCallPipeline modelPipeline = new ModelCallPipeline(
                model, hookDispatcher, toolRegistry, aroundHookChain, metrics);
        InterventionStore interventionStore = config.getInterventionStore() != null
                ? config.getInterventionStore()
                : new InMemoryInterventionStore();
        InterventionResolver interventionResolver = new InterventionResolver(
                interventionStore, toolOrch);
        LoopExecutor loopExecutor = new LoopExecutor(
                engine, modelPipeline, toolOrch, hookDispatcher, metrics,
                contextWindow.getEstimator(), interventionResolver);
        SessionGate sessionGate = config.getSessionGate() != null
                ? config.getSessionGate() : new LocalSessionGate();
        this.pipeline = new RequestPipeline(
                loopExecutor, stateStore, aroundHookChain,
                config.getExecutionConfig(), name, systemMessage, interventionStore, sessionGate);
    }

    /**
     * 非流式对话。发送消息并等待最终回复。
     *
     * @param messages 消息列表
     * @return 聊天响应
     */
    @Override
    public Mono<ChatResponse> chat(List<Msg> messages) {
        ensureBuilt();
        return pipeline.execute(messages, null);
    }

    /**
     * 非流式对话，指定运行时上下文（多租户/会话）。
     *
     * @param messages 消息列表
     * @param ctx      运行时上下文
     * @return 聊天响应
     */
    @Override
    public Mono<ChatResponse> chat(List<Msg> messages, RuntimeContext ctx) {
        ensureBuilt();
        return pipeline.execute(messages, ctx);
    }

    /**
     * 流式对话。发送消息并以 SSE 流式返回回复。
     *
     * @param messages 消息列表
     * @return 流式响应分块
     */
    @Override
    public Flux<ChatStreamChunk> stream(List<Msg> messages) {
        ensureBuilt();
        return pipeline.executeStream(messages, null);
    }

    /**
     * 流式对话，指定运行时上下文。
     *
     * @param messages 消息列表
     * @param ctx      运行时上下文
     * @return 流式响应分块
     */
    @Override
    public Flux<ChatStreamChunk> stream(List<Msg> messages, RuntimeContext ctx) {
        ensureBuilt();
        return pipeline.executeStream(messages, ctx);
    }

    /**
     * 发送消息并获取按指定类结构化的回复（注入 Schema 指令）。
     *
     * @param messages    消息列表
     * @param outputClass 期望的输出结构类
     * @return 聊天响应
     */
    @Override
    public Mono<ChatResponse> chat(List<Msg> messages, Class<?> outputClass) {
        return chat(StructuredOutputReminder.injectSchemaInstruction(messages, outputClass));
    }

    /**
     * 流式发送消息并获取按指定类结构化的回复。
     *
     * @param messages    消息列表
     * @param outputClass 期望的输出结构类
     * @return 流式响应分块
     */
    @Override
    public Flux<ChatStreamChunk> stream(List<Msg> messages, Class<?> outputClass) {
        return stream(StructuredOutputReminder.injectSchemaInstruction(messages, outputClass));
    }

    /**
     * 中断所有当前执行的请求。
     */
    @Override
    public void interrupt() {
        pipeline.interrupt();
    }

    /**
     * 中断所有当前执行的请求，并注入反馈消息供恢复时使用。
     *
     * @param feedbackMsg 反馈消息
     */
    @Override
    public void interrupt(Msg feedbackMsg) {
        for (LoopContext ctx : pipeline.getActiveRequests().values()) {
            ctx.interrupt(feedbackMsg);
        }
    }

    /**
     * 按会话 ID 中断指定会话的请求。
     *
     * @param sessionId 会话标识
     */
    public void interruptBySession(String sessionId) {
        pipeline.interruptBySession(sessionId);
    }

    /**
     * 构建 Agent。标记已构建状态并触发初始化回调。
     *
     * @return 构建完成的 Mono
     */
    public final Mono<Void> build() {
        if (built) {
            return Mono.error(new AgentConfigurationException(
                    UI.AGENT_PREFIX + name + UI.AGENT_ALREADY_BUILT));
        }
        built = true;
        return doBuild();
    }

    /**
     * 子类可扩展的构建逻辑。
     *
     * @return 构建完成的 Mono
     */
    protected Mono<Void> doBuild() { return Mono.empty(); }

    /**
     * 关闭 Agent，重置构建状态并清理活跃请求。
     *
     * @return 关闭完成的 Mono
     */
    public Mono<Void> shutdown() {
        built = false;
        pipeline.shutdown();
        return Mono.empty();
    }

    /**
     * 确保 Agent 已构建，否则抛出异常。
     */
    private void ensureBuilt() {
        if (!built) throw new AgentConfigurationException(
                UI.AGENT_PREFIX + name + UI.AGENT_NOT_BUILT);
    }

    /** @return Agent 名称 */
    @Override public String getName() { return name; }
    /** @return Agent 唯一 ID */
    @Override public String getId() { return id; }
    /** @return Agent 配置 */
    public AgentConfig getConfig() { return config; }
    /** @return 聊天模型 */
    public ChatModel getModel() { return config.getModel(); }
    /** @return 工具注册表 */
    public ToolRegistry getToolRegistry() { return config.getToolRegistry(); }
    /** @return Hook 链 */
    public HookChain getHookChain() { return hookDispatcher.getHookChain(); }
    /** @return 状态存储 */
    public AgentStateStore getStateStore() { return stateStore; }
    /** @return 上下文窗口 */
    public ModelContextWindow getContextWindow() { return contextWindow; }
    /** @return Hook 记录器 */
    public HookRecorder getHookRecorder() { return hookRecorder; }
    /** @return 指标收集器 */
    public AgentMetrics getMetrics() { return metrics; }
    /** @return 是否已构建 */
    public boolean isBuilt() { return built; }
    /** @return 是否有活跃请求 */
    public boolean isRunning() { return pipeline.isRunning(); }

    /** 设置系统提示消息 */
    public void setSystemMessage(String msg) { this.systemMessage = msg; }
    /** 设置 Hook 记录器 */
    public void setHookRecorder(HookRecorder v) {
        this.hookRecorder = v;
        if (this.hookDispatcher != null) this.hookDispatcher.setRecorder(v);
    }
    /** 设置指标收集器 */
    public void setMetrics(AgentMetrics v) { this.metrics = v != null ? v : AgentMetrics.NOOP; }
}
