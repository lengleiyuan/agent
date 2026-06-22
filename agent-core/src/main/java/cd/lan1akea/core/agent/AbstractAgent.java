package cd.lan1akea.core.agent;

import cd.lan1akea.core.agent.config.AgentConfig;
import cd.lan1akea.core.agent.config.AgentExecutionConfig;
import cd.lan1akea.core.agent.loop.LoopContext;
import cd.lan1akea.core.agent.loop.ReActLoop;
import cd.lan1akea.core.event.EventBus;
import cd.lan1akea.core.event.DomainEvent;
import cd.lan1akea.core.exception.AgentConfigurationException;
import cd.lan1akea.core.hook.HookChain;
import cd.lan1akea.core.hook.HookDispatcher;
import cd.lan1akea.core.hook.recorder.HookRecorder;
import cd.lan1akea.core.memory.Memory;
import cd.lan1akea.core.memory.InMemoryMemory;
import cd.lan1akea.core.middleware.MiddlewareChain;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.session.*;
import cd.lan1akea.core.shutdown.GracefulShutdown;
import cd.lan1akea.core.state.CheckpointService;
import cd.lan1akea.core.tenant.*;
import cd.lan1akea.core.tool.*;
import cd.lan1akea.core.util.IdGenerator;
import cd.lan1akea.core.workspace.Workspace;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/**
 * Agent 抽象基类。
 * <p>
 * 构建时一次性注入所有子系统。每次请求仅通过 LoopContext 传递可变数据。
 * 无反射，无运行时组件替换。遵循 AgentScope 的 AgentBase 设计模式。
 * </p>
 */
public abstract class AbstractAgent implements Agent, ObservableAgent, StreamableAgent, CallableAgent {

    // === 构建时注入 ===
    protected final String name;
    protected final AgentConfig config;
    protected final ChatModel model;
    protected final ToolRegistry toolRegistry;
    protected final HookChain hookChain;
    protected final MiddlewareChain middlewareChain;
    protected final EventBus eventBus;
    protected final AgentEventSource eventSource;
    protected final HookDispatcher hookDispatcher;
    protected final ToolExecutor toolExecutor;
    protected final ReActLoop reActLoop;

    // === 子系统（可通过 setter 替换） ===
    protected SessionStore sessionStore;
    protected SessionSummaryService summaryService;
    protected PermissionEngine permissionEngine;
    protected Memory memory;
    protected Workspace workspace;
    protected ModelContextWindow contextWindow;
    protected HookRecorder hookRecorder;
    protected GracefulShutdown gracefulShutdown;
    protected CheckpointService checkpointService;

    // === 状态 ===
    private volatile boolean built;
    private String currentSessionId;

    protected AbstractAgent(AgentConfig config) {
        cd.lan1akea.core.util.ValidationUtils.notNull(config, "AgentConfig");
        cd.lan1akea.core.util.ValidationUtils.notNull(config.getModel(), "ChatModel");

        this.config = config;
        this.name = config.getName();
        this.model = config.getModel();

        // 工具
        this.toolRegistry = config.getToolRegistry() != null ? config.getToolRegistry() : new ToolRegistry();
        this.toolExecutor = new ToolExecutor(toolRegistry);

        // Hook
        this.hookChain = config.getHookChain() != null ? config.getHookChain() : new HookChain();
        this.hookDispatcher = new HookDispatcher(this.hookChain);
        this.hookRecorder = new HookRecorder();

        // 中间件
        this.middlewareChain = config.getMiddlewareChain() != null ? config.getMiddlewareChain() : new MiddlewareChain();

        // 事件
        this.eventBus = new EventBus();
        this.eventSource = new AgentEventSource(eventBus);

        // 子系统默认值
        this.sessionStore = config.getSessionStore();
        this.summaryService = new SessionSummaryService();
        this.memory = new InMemoryMemory();
        this.contextWindow = new ModelContextWindow(model.getModelName(), 8000, 4000);
        this.gracefulShutdown = new GracefulShutdown(Duration.ofSeconds(30));

        // ReActLoop — 构建时一次性创建
        this.reActLoop = new ReActLoop(model, toolExecutor, hookDispatcher, toolRegistry,
            sessionStore, summaryService, memory, eventBus, hookRecorder, contextWindow);
    }

    // ========================================================================
    // 构建
    // ========================================================================

    public final Mono<Void> build() {
        if (built) return Mono.error(new AgentConfigurationException("Agent [" + name + "] 已构建"));
        built = true;

        gracefulShutdown.register(new cd.lan1akea.core.shutdown.ShutdownHook() {
            public String getName() { return name + "-shutdown"; }
            public Mono<Void> onShutdown() { return shutdown(); }
        });

        for (AgentEventType t : AgentEventType.values()) eventBus.subscribe("agent:" + t.name().toLowerCase());
        eventBus.subscribe("hook:*");

        return eventSource.emit(AgentEventType.CREATED, name).then(doBuild());
    }

    protected Mono<Void> doBuild() { return Mono.empty(); }

    // ========================================================================
    // 核心执行
    // ========================================================================

    @Override
    public Mono<ChatResponse> chat(List<Msg> messages, GenerateOptions options) {
        ensureBuilt();
        return Mono.deferContextual(ctxView -> {
            String tenantId = ctxView.getOrDefault("tenantId", null);
            String userId = ctxView.getOrDefault("userId", null);
            String sessId = ctxView.getOrDefault("sessionId", currentSessionId);

            LoopContext ctx = LoopContext.builder()
                .agentName(name).tenantId(tenantId).userId(userId).sessionId(sessId)
                .messages(messages).generateOptions(resolveOptions(options))
                .maxIterations(config.getExecutionConfig().getMaxIterations())
                .stream(false).build();

            return middlewareChain.applyBefore(ctx)
                .flatMap(reActLoop::execute)
                .flatMap(middlewareChain::applyAfter)
                .doOnSuccess(r -> eventSource.emit(AgentEventType.COMPLETED, name).subscribe())
                .doOnError(e -> eventSource.emit(AgentEventType.ERROR, name).subscribe());
        });
    }

    @Override
    public Flux<ChatStreamChunk> stream(List<Msg> messages, GenerateOptions options) {
        ensureBuilt();
        return Flux.deferContextual(ctxView -> {
            String tenantId = ctxView.getOrDefault("tenantId", null);
            String userId = ctxView.getOrDefault("userId", null);
            String sessId = ctxView.getOrDefault("sessionId", currentSessionId);

            GenerateOptions opts = resolveOptions(options);
            if (opts != null && !opts.isStream()) {
                opts = GenerateOptions.builder().temperature(opts.getTemperature())
                    .maxTokens(opts.getMaxTokens()).stream(true)
                    .toolChoice(opts.getToolChoice()).build();
            }

            LoopContext ctx = LoopContext.builder()
                .agentName(name).tenantId(tenantId).userId(userId).sessionId(sessId)
                .messages(messages).generateOptions(opts)
                .maxIterations(config.getExecutionConfig().getMaxIterations())
                .stream(true).build();

            return middlewareChain.applyBefore(ctx)
                .flatMapMany(reActLoop::executeStream)
                .doOnComplete(() -> eventSource.emit(AgentEventType.COMPLETED, name).subscribe())
                .doOnError(e -> eventSource.emit(AgentEventType.ERROR, name).subscribe());
        });
    }

    // ========================================================================
    // 会话
    // ========================================================================

    public Mono<Session> openSession(String sessionId) {
        if (sessionStore == null) {
            this.currentSessionId = IdGenerator.nextIdStr();
            return Mono.just(new Session(new SessionId(this.currentSessionId), 0, name,
                SessionState.ACTIVE, null, null, null));
        }
        if (sessionId != null) {
            SessionId sid = new SessionId(sessionId);
            return sessionStore.findById(sid)
                .switchIfEmpty(Mono.defer(() -> sessionStore.create(
                    new Session(sid, 0, name, SessionState.ACTIVE, null, null, null))))
                .doOnNext(s -> this.currentSessionId = s.getId().getValue());
        }
        String newId = IdGenerator.nextIdStr();
        SessionId sid = new SessionId(newId);
        return sessionStore.create(new Session(sid, 0, name, SessionState.ACTIVE, null, null, null))
            .doOnNext(s -> this.currentSessionId = s.getId().getValue());
    }

    // ========================================================================
    // 生命周期
    // ========================================================================

    public Mono<Void> shutdown() {
        built = false;
        if (currentSessionId != null && sessionStore != null) {
            return sessionStore.close(new SessionId(currentSessionId));
        }
        return Mono.empty();
    }

    @Override
    public Flux<DomainEvent> events() {
        return Flux.merge(
            eventBus.subscribe("agent:created"),
            eventBus.subscribe("agent:completed"),
            eventBus.subscribe("agent:error"),
            eventBus.subscribe("hook:*"));
    }

    @Override
    public Flux<DomainEvent> events(String eventType) { return eventBus.subscribe(eventType); }

    // ========================================================================
    // Setter
    // ========================================================================

    public void setSessionStore(SessionStore v) { this.sessionStore = v; }
    public void setSummaryService(SessionSummaryService v) { this.summaryService = v; }
    public void setPermissionEngine(PermissionEngine v) { this.permissionEngine = v; }
    public void setMemory(Memory v) { this.memory = v; }
    public void setWorkspace(Workspace v) { this.workspace = v; }
    public void setContextWindow(ModelContextWindow v) { this.contextWindow = v; }
    public void setHookRecorder(HookRecorder v) { this.hookRecorder = v; }
    public void setGracefulShutdown(GracefulShutdown v) { this.gracefulShutdown = v; }
    public void setCheckpointService(CheckpointService v) { this.checkpointService = v; }

    // ========================================================================
    // Getters
    // ========================================================================

    @Override public String getName() { return name; }
    public AgentConfig getConfig() { return config; }
    public ChatModel getModel() { return model; }
    public ToolRegistry getToolRegistry() { return toolRegistry; }
    public HookChain getHookChain() { return hookChain; }
    public EventBus getEventBus() { return eventBus; }
    public SessionStore getSessionStore() { return sessionStore; }
    public SessionSummaryService getSummaryService() { return summaryService; }
    public PermissionEngine getPermissionEngine() { return permissionEngine; }
    public Memory getMemory() { return memory; }
    public Workspace getWorkspace() { return workspace; }
    public ModelContextWindow getContextWindow() { return contextWindow; }
    public HookRecorder getHookRecorder() { return hookRecorder; }
    public GracefulShutdown getGracefulShutdown() { return gracefulShutdown; }
    public CheckpointService getCheckpointService() { return checkpointService; }
    public boolean isBuilt() { return built; }
    public String getCurrentSessionId() { return currentSessionId; }

    // ========================================================================
    // 内部
    // ========================================================================

    private GenerateOptions resolveOptions(GenerateOptions options) {
        if (options != null) return options;
        AgentExecutionConfig ec = config.getExecutionConfig();
        return GenerateOptions.builder().temperature(ec.getTemperature())
            .maxTokens(ec.getMaxTokens()).toolChoice(ec.getToolChoice()).build();
    }

    private void ensureBuilt() {
        if (!built) throw new AgentConfigurationException("Agent [" + name + "] 尚未构建");
    }
}
