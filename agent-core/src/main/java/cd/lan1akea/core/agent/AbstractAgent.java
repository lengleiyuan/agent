package cd.lan1akea.core.agent;

import cd.lan1akea.core.agent.config.AgentConfig;
import cd.lan1akea.core.agent.config.AgentExecutionConfig;
import cd.lan1akea.core.agent.loop.ReActLoop;
import cd.lan1akea.core.agent.loop.LoopContext;
import cd.lan1akea.core.event.EventBus;
import cd.lan1akea.core.event.DomainEvent;
import cd.lan1akea.core.exception.AgentConfigurationException;
import cd.lan1akea.core.hook.HookChain;
import cd.lan1akea.core.hook.HookDispatcher;
import cd.lan1akea.core.middleware.MiddlewareChain;
import cd.lan1akea.core.model.ChatModel;
import cd.lan1akea.core.model.ChatResponse;
import cd.lan1akea.core.model.ChatStreamChunk;
import cd.lan1akea.core.model.GenerateOptions;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.session.SessionStore;
import cd.lan1akea.core.tool.ToolRegistry;
import cd.lan1akea.core.tool.ToolExecutor;
import cd.lan1akea.core.util.ValidationUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Agent 抽象基类（模板方法模式）。
 * <p>
 * 封装 Agent 的完整生命周期：
 * <ol>
 * <li>构建（build）—— 注入模型、工具、Hook链、中间件链</li>
 * <li>执行（chat/stream）—— 经由 MiddlewareChain → ReActLoop → HookChain</li>
 * </ol>
 * 子类可覆写钩子方法定制行为。
 * </p>
 */
public abstract class AbstractAgent implements Agent, ObservableAgent, StreamableAgent, CallableAgent {

    /** Agent 名称 */
    protected final String name;

    /** Agent 配置 */
    protected final AgentConfig config;

    /** 聊天模型 */
    protected final ChatModel model;

    /** 工具注册表 */
    protected final ToolRegistry toolRegistry;

    /** 工具执行器 */
    protected final ToolExecutor toolExecutor;

    /** Hook 链 */
    protected final HookChain hookChain;

    /** Hook 调度器 */
    protected final HookDispatcher hookDispatcher;

    /** 中间件链 */
    protected final MiddlewareChain middlewareChain;

    /** 事件总线 */
    protected final EventBus eventBus;

    /** 事件源 */
    protected final AgentEventSource eventSource;

    /** ReAct 循环引擎 */
    protected final ReActLoop reActLoop;

    /** 会话存储（可选） */
    protected SessionStore sessionStore;

    /** 是否已构建 */
    private volatile boolean built = false;

    protected AbstractAgent(AgentConfig config) {
        ValidationUtils.notNull(config, "AgentConfig");
        ValidationUtils.notNull(config.getModel(), "ChatModel");

        this.config = config;
        this.name = config.getName();
        this.model = config.getModel();
        this.toolRegistry = config.getToolRegistry() != null
            ? config.getToolRegistry() : new ToolRegistry();
        this.toolExecutor = new ToolExecutor(this.toolRegistry);
        this.hookChain = config.getHookChain() != null
            ? config.getHookChain() : new HookChain();
        this.hookDispatcher = new HookDispatcher(this.hookChain);
        this.middlewareChain = config.getMiddlewareChain() != null
            ? config.getMiddlewareChain() : new MiddlewareChain();
        this.eventBus = new EventBus();
        this.eventSource = new AgentEventSource(eventBus);
        this.sessionStore = config.getSessionStore();
        this.reActLoop = createReActLoop();
    }

    /**
     * 构建 Agent，子类可在此进行额外的初始化验证。
     * 构建后状态变为 built=true，不可重复构建。
     */
    public final Mono<Void> build() {
        if (built) {
            return Mono.error(new AgentConfigurationException(
                "Agent [" + name + "] 已构建，不可重复构建"));
        }
        built = true;
        return eventSource.emit(AgentEventType.CREATED, name)
            .then(doBuild());
    }

    /**
     * 子类扩展构建过程。
     */
    protected Mono<Void> doBuild() {
        return Mono.empty();
    }

    // === 核心执行 ===

    @Override
    public Mono<ChatResponse> chat(List<Msg> messages, GenerateOptions options) {
        ensureBuilt();
    GenerateOptions opts = resolveOptions(options);
        LoopContext ctx = createLoopContext(messages, opts, false);

        return middlewareChain.applyBefore(ctx)
            .flatMap(this::executeChat)
            .flatMap(middlewareChain::applyAfter)
            .doOnSuccess(r -> eventSource.emit(AgentEventType.COMPLETED, name).subscribe())
            .doOnError(e -> eventSource.emit(AgentEventType.ERROR, name).subscribe());
    }

    @Override
    public Flux<ChatStreamChunk> stream(List<Msg> messages, GenerateOptions options) {
        ensureBuilt();
        GenerateOptions opts = resolveOptions(options);
        if (opts != null && !opts.isStream()) {
            opts = GenerateOptions.builder()
                .temperature(opts.getTemperature())
                .maxTokens(opts.getMaxTokens())
                .stream(true)
                .toolChoice(opts.getToolChoice())
                .build();
        }

        LoopContext ctx = createLoopContext(messages, opts, true);
        return middlewareChain.applyBefore(ctx)
            .flatMapMany(c -> executeStream(c))
            .doOnComplete(() -> eventSource.emit(AgentEventType.COMPLETED, name).subscribe())
            .doOnError(e -> eventSource.emit(AgentEventType.ERROR, name).subscribe());
    }

    /**
     * 执行单次对话的主流程。
     */
    protected Mono<ChatResponse> executeChat(LoopContext ctx) {
        return reActLoop.execute(ctx);
    }

    /**
     * 执行流式对话的主流程。
     */
    protected Flux<ChatStreamChunk> executeStream(LoopContext ctx) {
        return reActLoop.executeStream(ctx);
    }

    // === 生命周期 ===

    /**
     * 优雅关闭 Agent，释放资源。
     */
    public Mono<Void> shutdown() {
        return Mono.fromRunnable(() -> {
            built = false;
        });
    }

    // === 可观测能力 ===

    @Override
    public Flux<DomainEvent> events() {
        return Flux.empty(); // 子类覆写
    }

    @Override
    public Flux<DomainEvent> events(String eventType) {
        return eventBus.subscribe(eventType);
    }

    // === 受保护的工具方法 ===

    protected LoopContext createLoopContext(List<Msg> messages,
                                             GenerateOptions options, boolean stream) {
        return LoopContext.builder()
            .agentName(name)
            .messages(messages)
            .generateOptions(options)
            .stream(stream)
            .maxIterations(config.getExecutionConfig().getMaxIterations())
            .build();
    }

    protected ReActLoop createReActLoop() {
        return new ReActLoop(model, toolExecutor, hookDispatcher, toolRegistry);
    }

    protected GenerateOptions resolveOptions(GenerateOptions options) {
        if (options != null) return options;
        AgentExecutionConfig execConfig = config.getExecutionConfig();
        return GenerateOptions.builder()
            .temperature(execConfig.getTemperature())
            .maxTokens(execConfig.getMaxTokens())
            .toolChoice(execConfig.getToolChoice())
            .build();
    }

    protected void ensureBuilt() {
        if (!built) {
            throw new AgentConfigurationException(
                "Agent [" + name + "] 尚未构建，请先调用 build()");
        }
    }

    // === Getters ===

    @Override
    public String getName() { return name; }

    public AgentConfig getConfig() { return config; }
    public ChatModel getModel() { return model; }
    public ToolRegistry getToolRegistry() { return toolRegistry; }
    public HookChain getHookChain() { return hookChain; }
    public EventBus getEventBus() { return eventBus; }
    public SessionStore getSessionStore() { return sessionStore; }
    public boolean isBuilt() { return built; }
}
