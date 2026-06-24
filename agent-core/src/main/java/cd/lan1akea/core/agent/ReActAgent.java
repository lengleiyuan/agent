package cd.lan1akea.core.agent;

import cd.lan1akea.core.agent.config.AgentConfig;
import cd.lan1akea.core.agent.config.AgentExecutionConfig;
import cd.lan1akea.core.agent.loop.LoopContext;
import cd.lan1akea.core.agent.loop.ReActLoop;
import cd.lan1akea.core.context.RuntimeContext;
import cd.lan1akea.core.event.EventBus;
import cd.lan1akea.core.event.DomainEvent;
import cd.lan1akea.core.exception.AgentConfigurationException;
import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.hook.recorder.HookRecorder;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.SystemMessage;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.model.ChatResponse;
import cd.lan1akea.core.model.ChatStreamChunk;
import cd.lan1akea.core.model.ContextWindowManager;
import cd.lan1akea.core.model.ModelContextWindow;
import cd.lan1akea.core.model.StructuredOutputReminder;
import cd.lan1akea.core.session.*;
import cd.lan1akea.core.state.AgentState;
import cd.lan1akea.core.state.AgentStateStore;
import cd.lan1akea.core.tool.*;
import cd.lan1akea.core.util.IdGenerator;
import cd.lan1akea.core.workspace.Workspace;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ReActAgent — 多租户并发安全的 Agent 实现。
 * <p>
 * 构建时注入模型+工具+Hook，每次请求通过 LoopContext 传递可变数据。
 * 无实例级锁，多请求可并发执行。租户/用户/会话上下文通过 RuntimeContext 显式传递。
 * </p>
 */
public class ReActAgent implements ObservableAgent, StreamableAgent, CallableAgent {

    // === 构建时注入（不可变） ===
    final String id;
    final String name;
    final AgentConfig config;
    final ChatModel model;
    final ToolRegistry toolRegistry;
    final HookChain hookChain;
    final HookDispatcher hookDispatcher;
    final ToolExecutor toolExecutor;
    final ReActLoop reActLoop;

    // === 可选（可通过 setter 替换） ===
    AgentStateStore stateStore;
    Workspace workspace;
    SessionSummaryService summaryService;
    ModelContextWindow contextWindow;
    ContextWindowManager contextWindowManager;
    HookRecorder hookRecorder;

    // === 内部 ===
    final EventBus eventBus;
    final AgentEventSource eventSource;
    final AtomicReference<LoopContext> activeLoopContext = new AtomicReference<>();
    private volatile boolean built;

    public ReActAgent(AgentConfig config) {
        cd.lan1akea.core.util.ValidationUtils.notNull(config, "AgentConfig");
        cd.lan1akea.core.util.ValidationUtils.notNull(config.getModel(), "ChatModel");

        this.config = config;
        this.id = IdGenerator.nextIdStr();
        this.name = config.getName();
        this.model = config.getModel();

        this.toolRegistry = config.getToolRegistry() != null ? config.getToolRegistry() : new ToolRegistry();
        this.toolExecutor = new ToolExecutor(toolRegistry);

        this.hookChain = config.getHookChain() != null ? config.getHookChain() : new HookChain();
        this.hookDispatcher = new HookDispatcher(this.hookChain);

        this.eventBus = new EventBus();
        this.eventSource = new AgentEventSource(eventBus);

        this.stateStore = config.getStateStore();
        this.workspace = config.getWorkspace();
        this.contextWindow = new ModelContextWindow(config.getModel().getModelName(), 8000, 4000);
        this.contextWindowManager = new ContextWindowManager(contextWindow);
        this.summaryService = new SessionSummaryService();

        this.reActLoop = new ReActLoop(model, toolExecutor, hookDispatcher, toolRegistry,
            stateStore, eventBus);
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
    // 核心执行（非流式）
    // ========================================================================

    @Override
    public Mono<ChatResponse> chat(List<Msg> messages) {
        ensureBuilt();
        return Mono.deferContextual(ctxView -> {
            String tenantId = ctxView.getOrDefault("tenantId", null);
            String userId = ctxView.getOrDefault("userId", null);
            String sessId = ctxView.getOrDefault("sessionId", null);
            @SuppressWarnings("unchecked")
            Map<String, Object> attrs = ctxView.getOrDefault("attributes", null);

            return loadSessionAndHistory(sessId, messages)
                .flatMap(msgs -> injectAgentsMd(msgs))
                .flatMap(msgs -> dispatchPreCall(tenantId, userId, sessId, msgs))
                .flatMap(m -> {
                    LoopContext ctx = LoopContext.builder()
                        .agentName(name).tenantId(tenantId).userId(userId).sessionId(sessId)
                        .attributes(attrs).messages(m).generateOptions(resolveOptions())
                        .maxIterations(config.getExecutionConfig().getMaxIterations())
                        .stream(false).build();

                    activeLoopContext.set(ctx);
                    Mono<ChatResponse> exec = reActLoop.execute(ctx)
                        .doOnSuccess(r -> eventSource.emit(AgentEventType.COMPLETED, name).subscribe())
                        .doOnError(e -> eventSource.emit(AgentEventType.ERROR, name).subscribe())
                        .doFinally(s -> activeLoopContext.set(null));

                    long totalTimeout = config.getExecutionConfig().getTotalTimeoutMs();
                    if (totalTimeout > 0) exec = exec.timeout(Duration.ofMillis(totalTimeout));

                    return exec;
                })
                .flatMap(resp -> dispatchPostCall(tenantId, userId, sessId, resp, false));
        });
    }

    // ========================================================================
    // 核心执行：带 RuntimeContext
    // ========================================================================

    @Override
    public Mono<ChatResponse> chat(List<Msg> messages, RuntimeContext ctx) {
        return writeRuntimeContext(chat(messages), ctx);
    }

    @Override
    public Flux<ChatStreamChunk> stream(List<Msg> messages, RuntimeContext ctx) {
        return writeRuntimeContextStream(stream(messages), ctx);
    }

    private Mono<ChatResponse> writeRuntimeContext(Mono<ChatResponse> mono, RuntimeContext ctx) {
        return mono.contextWrite(c -> {
            if (ctx.getTenantId() != null) c = c.put("tenantId", ctx.getTenantId());
            if (ctx.getUserId() != null) c = c.put("userId", ctx.getUserId());
            if (ctx.getSessionId() != null) c = c.put("sessionId", ctx.getSessionId());
            if (!ctx.getAttributes().isEmpty()) c = c.put("attributes", ctx.getAttributes());
            return c;
        });
    }

    private Flux<ChatStreamChunk> writeRuntimeContextStream(Flux<ChatStreamChunk> flux, RuntimeContext ctx) {
        return flux.contextWrite(c -> {
            if (ctx.getTenantId() != null) c = c.put("tenantId", ctx.getTenantId());
            if (ctx.getUserId() != null) c = c.put("userId", ctx.getUserId());
            if (ctx.getSessionId() != null) c = c.put("sessionId", ctx.getSessionId());
            if (!ctx.getAttributes().isEmpty()) c = c.put("attributes", ctx.getAttributes());
            return c;
        });
    }

    // ========================================================================
    // 核心执行（流式）
    // ========================================================================

    @Override
    public Flux<ChatStreamChunk> stream(List<Msg> messages) {
        ensureBuilt();
        return Flux.deferContextual(ctxView -> {
            String tenantId = ctxView.getOrDefault("tenantId", null);
            String userId = ctxView.getOrDefault("userId", null);
            String sessId = ctxView.getOrDefault("sessionId", null);
            @SuppressWarnings("unchecked")
            Map<String, Object> attrs = ctxView.getOrDefault("attributes", null);

            GenerateOptions opts = resolveOptions();

            return loadSessionAndHistory(sessId, messages)
                .flatMapMany(msgs -> injectAgentsMd(msgs).flatMapMany(Flux::just))
                .concatMap(msgs -> dispatchPreCall(tenantId, userId, sessId, msgs))
                .concatMap(m -> {
                    LoopContext ctx = LoopContext.builder()
                        .agentName(name).tenantId(tenantId).userId(userId).sessionId(sessId)
                        .attributes(attrs).messages(m).generateOptions(opts)
                        .maxIterations(config.getExecutionConfig().getMaxIterations())
                        .stream(true).build();

                    activeLoopContext.set(ctx);
                    List<ChatStreamChunk> collected = new ArrayList<>();
                    return reActLoop.executeStream(ctx)
                        .doOnNext(collected::add)
                        .doOnComplete(() -> eventSource.emit(AgentEventType.COMPLETED, name).subscribe())
                        .doOnError(e -> eventSource.emit(AgentEventType.ERROR, name).subscribe())
                        .doFinally(s -> {
                            activeLoopContext.set(null);
                            dispatchPostCallStream(tenantId, userId, sessId, collected);
                        });
                });
        });
    }

    // ========================================================================
    // 结构化输出
    // ========================================================================

    @Override
    public Mono<ChatResponse> chat(List<Msg> messages, Class<?> outputClass) {
        return chat(StructuredOutputReminder.injectSchemaInstruction(messages, outputClass));
    }

    @Override
    public Flux<ChatStreamChunk> stream(List<Msg> messages, Class<?> outputClass) {
        return stream(StructuredOutputReminder.injectSchemaInstruction(messages, outputClass));
    }

    // ========================================================================
    // observe() —— 被动观察，不产生回复
    // ========================================================================

    @Override
    public Mono<Void> observe(Msg message) {
        ensureBuilt();
        return Mono.deferContextual(ctxView -> {
            String tenantId = ctxView.getOrDefault("tenantId", null);
            String userId = ctxView.getOrDefault("userId", null);

            LoopContext ctx = LoopContext.builder()
                .agentName(name).tenantId(tenantId).userId(userId)
                .messages(List.of(message))
                .generateOptions(resolveOptions())
                .maxIterations(1).stream(false).build();

            eventSource.emit(AgentEventType.STARTED, name).subscribe();
            return reActLoop.execute(ctx)
                .doOnSuccess(r -> eventSource.emit(AgentEventType.COMPLETED, name).subscribe())
                .doOnError(e -> eventSource.emit(AgentEventType.ERROR, name).subscribe())
                .then();
        });
    }

    // ========================================================================
    // 会话
    // ========================================================================

    public Mono<Session> openSession(String sessionId) {
        if (stateStore == null) {
            String newId = IdGenerator.nextIdStr();
            return Mono.just(new Session(new SessionId(newId), 0, name,
                SessionState.ACTIVE, null, null, null));
        }
        if (sessionId != null) {
            SessionId sid = new SessionId(sessionId);
            return stateStore.findById(sid)
                .switchIfEmpty(Mono.defer(() -> stateStore.create(
                    new Session(sid, 0, name, SessionState.ACTIVE, null, null, null))));
        }
        String newId = IdGenerator.nextIdStr();
        SessionId sid = new SessionId(newId);
        return stateStore.create(new Session(sid, 0, name, SessionState.ACTIVE, null, null, null));
    }

    // ========================================================================
    // 中断
    // ========================================================================

    @Override
    public void interrupt() {
        LoopContext ctx = activeLoopContext.get();
        if (ctx != null) ctx.interrupt();
    }

    @Override
    public void interrupt(Msg feedbackMsg) {
        LoopContext ctx = activeLoopContext.get();
        if (ctx != null) ctx.interrupt(feedbackMsg);
    }

    // ========================================================================
    // 生命周期
    // ========================================================================

    public Mono<Void> shutdown() {
        built = false;
        activeLoopContext.set(null);
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

    public void setStateStore(AgentStateStore v) { this.stateStore = v; }
    public void setWorkspace(Workspace v) { this.workspace = v; }
    public void setHookRecorder(HookRecorder v) {
        this.hookRecorder = v;
        if (this.hookDispatcher != null) this.hookDispatcher.setRecorder(v);
    }
    public void setContextWindowManager(ContextWindowManager v) { this.contextWindowManager = v; }

    // ========================================================================
    // Getters
    // ========================================================================

    @Override public String getName() { return name; }
    @Override public String getId() { return id; }
    public AgentConfig getConfig() { return config; }
    public ChatModel getModel() { return model; }
    public ToolRegistry getToolRegistry() { return toolRegistry; }
    public HookChain getHookChain() { return hookChain; }
    public EventBus getEventBus() { return eventBus; }
    public AgentStateStore getStateStore() { return stateStore; }
    public Workspace getWorkspace() { return workspace; }
    public SessionSummaryService getSummaryService() { return summaryService; }
    public ModelContextWindow getContextWindow() { return contextWindow; }
    public HookRecorder getHookRecorder() { return hookRecorder; }
    public boolean isBuilt() { return built; }
    public boolean isRunning() { return activeLoopContext.get() != null; }

    // ========================================================================
    // 内部：会话历史 + 检查点恢复
    // ========================================================================

    private Mono<List<Msg>> loadSessionAndHistory(String sessionId, List<Msg> messages) {
        if (sessionId == null || stateStore == null) return Mono.just(messages);

        return stateStore.findById(new SessionId(sessionId))
            .flatMap(session -> {
                return stateStore.loadLatestCheckpoint(sessionId)
                    .flatMap(checkpoint -> {
                        if (checkpoint.isShutdownInterrupted()) {
                            checkpoint.setShutdownInterrupted(false);
                            return stateStore.saveCheckpoint(checkpoint)
                                .thenReturn(checkpoint);
                        }
                        return Mono.just(checkpoint);
                    })
                    .flatMap(checkpoint -> {
                        List<Msg> restored = checkpoint.getMessages();
                        if (restored != null && !restored.isEmpty()) {
                            restored.addAll(messages);
                            return Mono.just(restored);
                        }
                        return loadHistory(sessionId, messages);
                    })
                    .switchIfEmpty(loadHistory(sessionId, messages));
            })
            .defaultIfEmpty(messages);
    }

    private Mono<List<Msg>> loadHistory(String sessionId, List<Msg> messages) {
        return stateStore.getHistory(new SessionId(sessionId))
            .collectList()
            .map(historyMsgs -> {
                List<Msg> all = new ArrayList<>(historyMsgs);
                all.addAll(messages);
                return all;
            })
            .defaultIfEmpty(messages);
    }

    // ========================================================================
    // 内部：AGENTS.md 自动注入
    // ========================================================================

    private Mono<List<Msg>> injectAgentsMd(List<Msg> messages) {
        if (workspace == null) return Mono.just(messages);
        try {
            String agentsMd = workspace.readAgentsMd();
            if (agentsMd != null && !agentsMd.isBlank()) {
                List<Msg> enriched = new ArrayList<>();
                enriched.add(SystemMessage.of(agentsMd));
                enriched.addAll(messages);
                return Mono.just(enriched);
            }
        } catch (Exception ignored) {}
        return Mono.just(messages);
    }

    // ========================================================================
    // 内部：PreCall / PostCall Hook 调度
    // ========================================================================

    private Mono<List<Msg>> dispatchPreCall(String tenantId, String userId, String sessionId,
                                             List<Msg> messages) {
        final List<Msg> finalMessages;
        if (contextWindowManager.isNearLimit(messages)) {
            finalMessages = contextWindowManager.trim(messages, 8);
        } else {
            finalMessages = messages;
        }
        if (hookChain.size() == 0) return Mono.just(finalMessages);

        HookContext hc = new HookContext(name, tenantId, sessionId, userId, 0, List.of(), null);
        PreCallEvent event = new PreCallEvent();
        event.setMessages(finalMessages);

        return hookDispatcher.dispatch(HookEventType.PRE_CALL, event, hc)
            .map(r -> {
                if (r.isAbort()) throw new AgentConfigurationException("PreCall Hook 阻止执行: " + r.getAbortReason());
                return r.isModify() && event.getMessages() != null
                    ? event.getMessages() : finalMessages;
            })
            .defaultIfEmpty(finalMessages);
    }

    private Mono<ChatResponse> dispatchPostCall(String tenantId, String userId, String sessionId,
                                                 ChatResponse response, boolean stream) {
        if (hookChain.size() == 0) return Mono.just(response);

        HookContext hc = new HookContext(name, tenantId, sessionId, userId, 0, List.of(), null);
        PostCallEvent event = new PostCallEvent();
        event.setChatResponse(response);
        event.setStream(stream);

        return hookDispatcher.dispatch(HookEventType.POST_CALL, event, hc)
            .thenReturn(response);
    }

    private void dispatchPostCallStream(String tenantId, String userId, String sessionId,
                                         List<ChatStreamChunk> chunks) {
        if (hookChain.size() == 0 || chunks.isEmpty()) return;

        HookContext hc = new HookContext(name, tenantId, sessionId, userId, 0, List.of(), null);
        PostCallEvent event = new PostCallEvent();
        event.setStreamChunks(chunks);
        event.setStream(true);

        hookDispatcher.dispatch(HookEventType.POST_CALL, event, hc).subscribe();
    }

    // ========================================================================
    // 内部：生成选项
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
