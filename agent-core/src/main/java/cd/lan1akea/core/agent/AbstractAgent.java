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
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.session.*;
import cd.lan1akea.core.tool.*;
import cd.lan1akea.core.tenant.PermissionEngine;
import cd.lan1akea.core.util.IdGenerator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Agent 抽象基类。
 * <p>
 * 构建时注入模型+工具+Hook，每次请求仅通过 LoopContext 传递可变数据。
 * 无反射，无上帝对象。Hook 体系负责所有扩展（压缩/记忆/过滤/审计/限流）。
 * </p>
 */
public abstract class AbstractAgent implements Agent, ObservableAgent, StreamableAgent, CallableAgent {

    // === 构建时注入（不可变） ===
    final String name;
    final AgentConfig config;
    final ChatModel model;
    final ToolRegistry toolRegistry;
    final HookChain hookChain;
    final HookDispatcher hookDispatcher;
    final ToolExecutor toolExecutor;
    final ReActLoop reActLoop;

    // === 可选（可通过 setter 替换） ===
    SessionStore sessionStore;
    PermissionEngine permissionEngine;
    HookRecorder hookRecorder;

    // === 内部 ===
    final EventBus eventBus;
    final AgentEventSource eventSource;
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

        // 事件
        this.eventBus = new EventBus();
        this.eventSource = new AgentEventSource(eventBus);

        // 会话
        this.sessionStore = config.getSessionStore();

        // ReActLoop — 构建时一次性创建
        this.reActLoop = new ReActLoop(model, toolExecutor, hookDispatcher, toolRegistry,
            sessionStore, eventBus, hookRecorder);
    }

    // ========================================================================
    // 构建
    // ========================================================================

    public final Mono<Void> build() {
        if (built) return Mono.error(new AgentConfigurationException("Agent [" + name + "] 已构建"));
        built = true;

        for (AgentEventType t : AgentEventType.values()) eventBus.subscribe("agent:" + t.name().toLowerCase());
        eventBus.subscribe("hook:*");

        return eventSource.emit(AgentEventType.CREATED, name).then(doBuild());
    }

    protected Mono<Void> doBuild() { return Mono.empty(); }

    // ========================================================================
    // 核心执行
    // ========================================================================

    @Override
    public Mono<ChatResponse> chat(List<Msg> messages) {
        ensureBuilt();
        return Mono.deferContextual(ctxView -> {
            String tenantId = ctxView.getOrDefault("tenantId", null);
            String userId = ctxView.getOrDefault("userId", null);
            String sessId = ctxView.getOrDefault("sessionId", currentSessionId);

            LoopContext ctx = LoopContext.builder()
                .agentName(name).tenantId(tenantId).userId(userId).sessionId(sessId)
                .messages(messages).generateOptions(resolveOptions())
                .maxIterations(config.getExecutionConfig().getMaxIterations())
                .stream(false).build();

            return reActLoop.execute(ctx)
                .doOnSuccess(r -> eventSource.emit(AgentEventType.COMPLETED, name).subscribe())
                .doOnError(e -> eventSource.emit(AgentEventType.ERROR, name).subscribe());
        });
    }

    @Override
    public Flux<ChatStreamChunk> stream(List<Msg> messages) {
        ensureBuilt();
        return Flux.deferContextual(ctxView -> {
            String tenantId = ctxView.getOrDefault("tenantId", null);
            String userId = ctxView.getOrDefault("userId", null);
            String sessId = ctxView.getOrDefault("sessionId", currentSessionId);

            GenerateOptions opts = resolveOptions();

            LoopContext ctx = LoopContext.builder()
                .agentName(name).tenantId(tenantId).userId(userId).sessionId(sessId)
                .messages(messages).generateOptions(opts)
                .maxIterations(config.getExecutionConfig().getMaxIterations())
                .stream(true).build();

            return reActLoop.executeStream(ctx)
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
    public void setPermissionEngine(PermissionEngine v) { this.permissionEngine = v; }
    public void setHookRecorder(HookRecorder v) { this.hookRecorder = v; }

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
    public PermissionEngine getPermissionEngine() { return permissionEngine; }
    public HookRecorder getHookRecorder() { return hookRecorder; }
    public boolean isBuilt() { return built; }
    public String getCurrentSessionId() { return currentSessionId; }

    // ========================================================================
    // 内部
    // ========================================================================

    private GenerateOptions resolveOptions() {
        AgentExecutionConfig ec = config.getExecutionConfig();
        return GenerateOptions.builder().temperature(ec.getTemperature())
            .maxTokens(ec.getMaxTokens()).toolChoice(ec.getToolChoice()).build();
    }

    private void ensureBuilt() {
        if (!built) throw new AgentConfigurationException("Agent [" + name + "] 尚未构建");
    }
}
