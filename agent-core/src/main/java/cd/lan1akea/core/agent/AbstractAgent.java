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
import cd.lan1akea.core.state.InMemoryStateStore;
import cd.lan1akea.core.tenant.*;
import cd.lan1akea.core.tool.*;
import cd.lan1akea.core.util.IdGenerator;
import cd.lan1akea.core.workspace.Workspace;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/**
 * Agent 抽象基类（完整版）。
 * <p>
 * 封装 Agent 的完整生命周期：
 * <ol>
 * <li>构建（build）— 注入所有子系统：模型、工具、Hook链、中间件、权限、记忆、会话存储</li>
 * <li>执行（chat/stream）— 经由 MiddlewareChain → ReActLoop → HookChain → 持久化</li>
 * <li>关闭（shutdown）— 保存状态、释放资源</li>
 * </ol>
 * </p>
 */
public abstract class AbstractAgent implements Agent, ObservableAgent, StreamableAgent, CallableAgent {

    // === 核心组件 ===
    protected final String name;
    protected final AgentConfig config;
    protected final ChatModel model;
    protected final ToolRegistry toolRegistry;
    protected final ToolExecutor toolExecutor;
    protected final HookChain hookChain;
    protected final HookDispatcher hookDispatcher;
    protected final MiddlewareChain middlewareChain;
    protected final EventBus eventBus;
    protected final AgentEventSource eventSource;
    protected final ReActLoop reActLoop;

    // === 子系统 ===
    protected SessionStore sessionStore;
    protected SessionSummaryService summaryService;
    protected PermissionEngine permissionEngine;
    protected Memory memory;
    protected Workspace workspace;
    protected ModelContextWindow contextWindow;
    protected ToolValidator toolValidator;
    protected HookRecorder hookRecorder;
    protected GracefulShutdown gracefulShutdown;
    protected CheckpointService checkpointService;

    // === 状态 ===
    private volatile boolean built = false;
    private String currentSessionId;

    protected AbstractAgent(AgentConfig config) {
        cd.lan1akea.core.util.ValidationUtils.notNull(config, "AgentConfig");
        cd.lan1akea.core.util.ValidationUtils.notNull(config.getModel(), "ChatModel");

        this.config = config;
        this.name = config.getName();
        this.model = config.getModel();

        // 工具系统
        this.toolRegistry = config.getToolRegistry() != null
            ? config.getToolRegistry() : new ToolRegistry();
        this.toolValidator = new ToolValidator();
        this.toolExecutor = new ToolExecutor(toolRegistry, new DefaultToolEmitter(),
            toolValidator, null, null);

        // Hook 系统
        this.hookChain = config.getHookChain() != null
            ? config.getHookChain() : new HookChain();
        this.hookDispatcher = new HookDispatcher(this.hookChain);
        this.hookRecorder = new HookRecorder();

        // 中间件
        this.middlewareChain = config.getMiddlewareChain() != null
            ? config.getMiddlewareChain() : new MiddlewareChain();

        // 事件总线
        this.eventBus = new EventBus();
        this.eventSource = new AgentEventSource(eventBus);

        // 默认子系统（可被 setter 覆盖）
        this.sessionStore = config.getSessionStore();
        this.summaryService = new SessionSummaryService();
        this.memory = new InMemoryMemory();
        this.contextWindow = new ModelContextWindow(model.getModelName(), 8000, 4000);
        this.gracefulShutdown = new GracefulShutdown(Duration.ofSeconds(30));

        // ReAct 循环
        this.reActLoop = createReActLoop();
    }

    // === 构建生命周期 ===

    public final Mono<Void> build() {
        if (built) {
            return Mono.error(new AgentConfigurationException(
                "Agent [" + name + "] 已构建，不可重复构建"));
        }
        built = true;

        // 注册停机钩子
        gracefulShutdown.register(new cd.lan1akea.core.shutdown.ShutdownHook() {
            @Override
            public String getName() { return name + "-shutdown"; }
            @Override
            public Mono<Void> onShutdown() {
                return shutdown();
            }
        });

        // 注册事件类型
        for (AgentEventType type : AgentEventType.values()) {
            eventBus.subscribe("agent:" + type.name().toLowerCase());
        }
        eventBus.subscribe("hook:*");

        return eventSource.emit(AgentEventType.CREATED, name)
            .then(doBuild());
    }

    protected Mono<Void> doBuild() { return Mono.empty(); }

    // ========================================================================
    // 核心执行
    // ========================================================================

    @Override
    public Mono<ChatResponse> chat(List<Msg> messages, GenerateOptions options) {
        ensureBuilt();
        // 从 Reactor Context 读取租户信息（由调用方通过 .contextWrite 注入）
        return Mono.deferContextual(ctxView -> {
            String tenantId = ctxView.getOrDefault("tenantId", null);
            String userId = ctxView.getOrDefault("userId", null);
            String sessId = ctxView.getOrDefault("sessionId", currentSessionId);

            GenerateOptions opts = resolveOptions(options);
            LoopContext ctx = createLoopContext(messages, opts, false, tenantId, userId, sessId);

            return middlewareChain.applyBefore(ctx)
                .flatMap(this::executeChat)
                .flatMap(middlewareChain::applyAfter)
                .flatMap(response -> {
                    if (sessId != null && sessionStore != null) {
                        persistTurn(ctx, response);
                    }
                    return Mono.just(response);
                })
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
                opts = GenerateOptions.builder()
                    .temperature(opts.getTemperature())
                    .maxTokens(opts.getMaxTokens())
                    .stream(true)
                    .toolChoice(opts.getToolChoice())
                    .build();
            }
            LoopContext ctx = createLoopContext(messages, opts, true, tenantId, userId, sessId);
            return middlewareChain.applyBefore(ctx)
                .flatMapMany(this::executeStream)
                .doOnComplete(() -> eventSource.emit(AgentEventType.COMPLETED, name).subscribe())
                .doOnError(e -> eventSource.emit(AgentEventType.ERROR, name).subscribe());
        });
    }

    // ========================================================================
    // 会话管理
    // ========================================================================

    /**
     * 创建或恢复会话。
     *
     * @param sessionId 会话ID（null 则创建新会话）
     * @return Mono&lt;Session&gt;
     */
    public Mono<Session> openSession(String sessionId) {
        if (sessionStore == null) {
            this.currentSessionId = IdGenerator.nextIdStr();
            return Mono.just(new Session(
            new SessionId(this.currentSessionId),
                0, name, SessionState.ACTIVE, null, null, null));
        }

        if (sessionId != null) {
            SessionId sid = new SessionId(sessionId);
            return sessionStore.findById(sid)
                .switchIfEmpty(Mono.defer(() -> {
                    Session newSession = new Session(sid, 0, name,
                        SessionState.ACTIVE, null, null, null);
                    return sessionStore.create(newSession);
                }))
                .doOnNext(s -> this.currentSessionId = s.getId().getValue());
        } else {
            String newId = IdGenerator.nextIdStr();
            SessionId sid = new SessionId(newId);
            Session newSession = new Session(sid, 0, name,
                SessionState.ACTIVE, null, null, null);
            return sessionStore.create(newSession)
                .doOnNext(s -> this.currentSessionId = s.getId().getValue());
        }
    }

    // ========================================================================
    // 受保护方法（子类可覆写）
    // ========================================================================

    protected Mono<ChatResponse> executeChat(LoopContext ctx) {
        return reActLoop.execute(ctx);
    }

    protected Flux<ChatStreamChunk> executeStream(LoopContext ctx) {
        return reActLoop.executeStream(ctx);
    }

    protected LoopContext createLoopContext(List<Msg> messages,
                                             GenerateOptions options, boolean stream,
                                             String tenantId, String userId, String sessionId) {
        // 注入 ToolExecutor 的上下文提供者（使用真实租户/用户信息）
        ToolExecutionContextProvider contextProvider = () ->
            new ToolExecutionContext(tenantId, userId, sessionId, name);

        // 重建 ToolExecutor（注入真实权限和上下文）
        ToolExecutor ctxToolExecutor = new ToolExecutor(
            toolRegistry, new DefaultToolEmitter(), toolValidator,
            permissionEngine, contextProvider);

        // 重建 ReActLoop
        ReActLoop ctxReActLoop = new ReActLoop(model, ctxToolExecutor,
            hookDispatcher, toolRegistry);
        try {
            java.lang.reflect.Field loopField = AbstractAgent.class.getDeclaredField("reActLoop");
            loopField.setAccessible(true);
            loopField.set(this, ctxReActLoop);
        } catch (Exception ignored) {}

        return LoopContext.builder()
            .agentName(name)
            .tenantId(tenantId)
            .userId(userId)
            .sessionId(sessionId)
            .messages(messages)
            .generateOptions(options)
            .maxIterations(config.getExecutionConfig().getMaxIterations())
            .stream(stream)
            .sessionStore(sessionStore)
            .summaryService(summaryService)
            .permissionEngine(permissionEngine)
            .eventBus(eventBus)
            .memory(memory)
            .workspace(workspace)
            .contextWindow(contextWindow)
            .toolValidator(toolValidator)
            .hookRecorder(hookRecorder)
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

    protected void persistTurn(LoopContext ctx, ChatResponse response) {
        if (currentSessionId == null || sessionStore == null) return;
        SessionId sid = new SessionId(currentSessionId);
        String userJson = ctx.getMessages().isEmpty() ? "" :
            ctx.getMessages().get(ctx.getMessages().size() - 1).getTextContent();
        String assistantJson = response.getMessage().getTextContent();
        ChatTurn turn = new ChatTurn(
            IdGenerator.nextId(),
            Long.parseLong(currentSessionId),
            ctx.getIteration(),
            userJson, assistantJson, null,
            java.time.LocalDateTime.now());
        sessionStore.addTurn(sid, turn).subscribe();
    }

    // ========================================================================
    // 生命周期 + 可观测
    // ========================================================================

    public Mono<Void> shutdown() {
        built = false;
        return Mono.fromRunnable(() -> {
            if (currentSessionId != null && sessionStore != null) {
                sessionStore.close(new SessionId(currentSessionId)).subscribe();
            }
        });
    }

    @Override
    public Flux<DomainEvent> events() {
        return Flux.merge(
            eventBus.subscribe("agent:created"),
            eventBus.subscribe("agent:started"),
            eventBus.subscribe("agent:completed"),
            eventBus.subscribe("agent:error"),
            eventBus.subscribe("hook:*"));
    }

    @Override
    public Flux<DomainEvent> events(String eventType) {
        return eventBus.subscribe(eventType);
    }

    // ========================================================================
    // Setter（注入子系统）
    // ========================================================================

    public void setSessionStore(SessionStore sessionStore) { this.sessionStore = sessionStore; }
    public void setSummaryService(SessionSummaryService summaryService) { this.summaryService = summaryService; }
    public void setPermissionEngine(PermissionEngine permissionEngine) {
        this.permissionEngine = permissionEngine;
        // 更新 ToolExecutor
        ToolExecutionContextProvider cp = () -> new ToolExecutionContext(null, null, currentSessionId, name);
        try {
            java.lang.reflect.Field f = AbstractAgent.class.getDeclaredField("toolExecutor");
            f.setAccessible(true);
            f.set(this, new ToolExecutor(toolRegistry, new DefaultToolEmitter(),
                toolValidator, permissionEngine, cp));
        } catch (Exception ignored) {}
    }
    public void setMemory(Memory memory) { this.memory = memory; }
    public void setWorkspace(Workspace workspace) { this.workspace = workspace; }
    public void setContextWindow(ModelContextWindow contextWindow) { this.contextWindow = contextWindow; }
    public void setToolValidator(ToolValidator toolValidator) { this.toolValidator = toolValidator; }
    public void setHookRecorder(HookRecorder hookRecorder) { this.hookRecorder = hookRecorder; }
    public void setGracefulShutdown(GracefulShutdown gracefulShutdown) { this.gracefulShutdown = gracefulShutdown; }
    public void setCheckpointService(CheckpointService checkpointService) { this.checkpointService = checkpointService; }

    // ========================================================================
    // Getters
    // ========================================================================

    @Override
    public String getName() { return name; }
    public AgentConfig getConfig() { return config; }
    public ChatModel getModel() { return model; }
    public ToolRegistry getToolRegistry() { return toolRegistry; }
    public HookChain getHookChain() { return hookChain; }
    public EventBus getEventBus() { return eventBus; }
    public SessionStore getSessionStore() { return sessionStore; }
    public boolean isBuilt() { return built; }
    public String getCurrentSessionId() { return currentSessionId; }
    public SessionSummaryService getSummaryService() { return summaryService; }
    public PermissionEngine getPermissionEngine() { return permissionEngine; }
    public Memory getMemory() { return memory; }
    public Workspace getWorkspace() { return workspace; }
    public ModelContextWindow getContextWindow() { return contextWindow; }
    public HookRecorder getHookRecorder() { return hookRecorder; }
    public GracefulShutdown getGracefulShutdown() { return gracefulShutdown; }
    public CheckpointService getCheckpointService() { return checkpointService; }

    protected void ensureBuilt() {
        if (!built) {
            throw new AgentConfigurationException(
                "Agent [" + name + "] 尚未构建，请先调用 build()");
        }
    }
}
