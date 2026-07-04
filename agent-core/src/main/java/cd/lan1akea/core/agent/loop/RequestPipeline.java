package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.CoreConstants.Defaults;
import cd.lan1akea.core.CoreConstants.RuntimeCtx;
import cd.lan1akea.core.agent.config.AgentExecutionConfig;
import cd.lan1akea.core.context.RuntimeContext;
import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.SystemMessage;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.session.*;
import cd.lan1akea.core.state.AgentStateStore;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 请求预处理管线。
 * resolveContext → aroundCall → loadSession → injectSystemMessage → buildLoopCtx → execute
 */
public class RequestPipeline {

    private final LoopExecutor loopExecutor;
    private final AgentStateStore stateStore;
    private final AroundHookChain aroundHookChain;
    private final AgentExecutionConfig execConfig;
    private final String agentName;
    private final String systemMessage;
    private final ConcurrentHashMap<String, LoopContext> activeRequests;

    public RequestPipeline(LoopExecutor loopExecutor, AgentStateStore stateStore,
                            AroundHookChain aroundHookChain, AgentExecutionConfig execConfig,
                            String agentName, String systemMessage) {
        this.loopExecutor = loopExecutor;
        this.stateStore = stateStore;
        this.aroundHookChain = aroundHookChain;
        this.execConfig = execConfig;
        this.agentName = agentName;
        this.systemMessage = systemMessage;
        this.activeRequests = new ConcurrentHashMap<>();
    }

    public Flux<ChatStreamChunk> executeStream(List<Msg> messages, RuntimeContext rtCtx) {
        return Flux.deferContextual(ctxView -> {
            RuntimeContext ctx = resolveContext(ctxView, rtCtx);
            HookContext callHc = HookContext.from(ctx, 0);
            GenerateOptions opts = resolveOptions();

            return aroundHookChain.aroundReasoningStream(new HookEvent(null), callHc,
                    e -> loadSessionAndHistory(ctx, messages)
                            .flatMapMany(msgs -> injectSystemMessage(msgs).flatMapMany(Flux::just))
                            .concatMap(m -> {
                                LoopContext loopCtx = LoopContextFactory.create(
                                        agentName, ctx, m, opts, execConfig, true);
                                activeRequests.put(loopCtx.getRequestId(), loopCtx);
                                Flux<ChatStreamChunk> stream = loopExecutor.runStream(loopCtx)
                                        .doFinally(s -> activeRequests.remove(loopCtx.getRequestId()));
                                long timeout = execConfig.getTotalTimeoutMs();
                                if (timeout > 0) stream = stream.timeout(Duration.ofMillis(timeout));
                                return stream;
                            })
                            .contextWrite(c -> writeContext(c, ctx)));
        });
    }

    public Mono<ChatResponse> execute(List<Msg> messages, RuntimeContext rtCtx) {
        return Mono.deferContextual(ctxView -> {
            final RuntimeContext ctx = resolveContext(ctxView, rtCtx);
            HookContext callHc = HookContext.from(ctx, 0);
            return aroundHookChain.aroundReasoning(new HookEvent(null), callHc,
                    e -> loadSessionAndHistory(ctx, messages)
                            .flatMap(this::injectSystemMessage)
                            .flatMap(m -> {
                                LoopContext loopCtx = LoopContextFactory.create(
                                        agentName, ctx, m, resolveOptions(), execConfig, false);
                                activeRequests.put(loopCtx.getRequestId(), loopCtx);
                                Mono<ChatResponse> exec = loopExecutor.run(loopCtx)
                                        .doFinally(s -> activeRequests.remove(loopCtx.getRequestId()));
                                long timeout = execConfig.getTotalTimeoutMs();
                                if (timeout > 0) exec = exec.timeout(Duration.ofMillis(timeout));
                                return exec;
                            })
                            .map(resp -> { e.setPayload("response", resp); return e; }))
                    .map(e -> (ChatResponse) e.getPayload("response"))
                    .contextWrite(c -> writeContext(c, ctx));
        });
    }

    public void interrupt() {
        for (LoopContext ctx : activeRequests.values()) {
            ctx.interrupt();
        }
    }

    public void interruptBySession(String sessionId) {
        for (LoopContext ctx : activeRequests.values()) {
            if (sessionId != null && sessionId.equals(ctx.getSessionId())) {
                ctx.interrupt();
            }
        }
    }

    public boolean isRunning() { return !activeRequests.isEmpty(); }
    public ConcurrentHashMap<String, LoopContext> getActiveRequests() { return activeRequests; }
    public void shutdown() { activeRequests.clear(); }

    // ---- private helpers (same as original ReActAgent) ----

    private RuntimeContext resolveContext(reactor.util.context.ContextView ctxView, RuntimeContext rtCtx) {
        if (rtCtx != null) return rtCtx;
        String requestId = ctxView.getOrDefault(RuntimeCtx.REQUEST_ID, (String) null);
        String tenantId = ctxView.getOrDefault(RuntimeCtx.TENANT_ID, (String) null);
        String userId = ctxView.getOrDefault(RuntimeCtx.USER_ID, (String) null);
        String sessionId = ctxView.getOrDefault(RuntimeCtx.SESSION_ID, (String) null);
        Map<String, Object> attributes = ctxView.getOrDefault(RuntimeCtx.ATTRIBUTES, (Map<String, Object>) null);
        return new RuntimeContext(requestId, tenantId, userId, sessionId, agentName, attributes);
    }

    private reactor.util.context.Context writeContext(reactor.util.context.Context c, RuntimeContext ctx) {
        if (ctx.getTenantId() != null) c = c.put(RuntimeCtx.TENANT_ID, ctx.getTenantId());
        if (ctx.getUserId() != null) c = c.put(RuntimeCtx.USER_ID, ctx.getUserId());
        if (ctx.getSessionId() != null) c = c.put(RuntimeCtx.SESSION_ID, ctx.getSessionId());
        if (!ctx.getAttributes().isEmpty()) c = c.put(RuntimeCtx.ATTRIBUTES, ctx.getAttributes());
        return c;
    }

    private Mono<List<Msg>> loadSessionAndHistory(RuntimeContext ctx, List<Msg> messages) {
        String sessionId = ctx.getSessionId();
        if (sessionId == null || stateStore == null) return Mono.just(messages);

        SessionId sid = new SessionId(sessionId);
        String tenantId = ctx.getTenantId() != null ? ctx.getTenantId() : Defaults.TENANT;

        return stateStore.findById(sid)
                .flatMap(session -> stateStore.loadLatestCheckpoint(sessionId)
                        .flatMap(checkpoint -> {
                            if (checkpoint.isShutdownInterrupted()) {
                                checkpoint.setShutdownInterrupted(false);
                                return stateStore.saveCheckpoint(checkpoint).thenReturn(checkpoint);
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
                        .switchIfEmpty(loadHistory(sessionId, messages)))
                .switchIfEmpty(
                        stateStore.create(new Session(sid, tenantId, agentName,
                                        SessionState.ACTIVE, null, null, null))
                                .then(Mono.just(messages)));
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

    private Mono<List<Msg>> injectSystemMessage(List<Msg> messages) {
        String sysMsg = systemMessage;
        if (sysMsg != null && !sysMsg.isBlank()) {
            List<Msg> enriched = new ArrayList<>();
            enriched.add(SystemMessage.of(sysMsg));
            enriched.addAll(messages);
            return Mono.just(enriched);
        }
        return Mono.just(messages);
    }

    private GenerateOptions resolveOptions() {
        return GenerateOptions.builder()
                .temperature(execConfig.getTemperature())
                .maxTokens(execConfig.getMaxTokens())
                .toolChoice(execConfig.getToolChoice())
                .build();
    }
}
