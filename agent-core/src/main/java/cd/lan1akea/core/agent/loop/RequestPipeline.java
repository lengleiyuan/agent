package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.CoreConstants.Defaults;
import cd.lan1akea.core.CoreConstants.EventPayload;
import cd.lan1akea.core.CoreConstants.Intervention;
import cd.lan1akea.core.CoreConstants.RuntimeCtx;
import cd.lan1akea.core.agent.config.AgentExecutionConfig;
import cd.lan1akea.core.context.RuntimeContext;
import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.intervention.InterventionRequest;
import cd.lan1akea.core.intervention.InterventionStore;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.SystemMessage;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.session.*;
import cd.lan1akea.core.state.AgentState;
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
 *
 * <p>将原始消息列表经过完整的预处理流水线转换为 Agent 执行结果：
 * <ol>
 *   <li>resolveContext —— 从 Reactor Context 或传入参数解析多租户身份</li>
 *   <li>aroundCall —— 以 AroundHook 洋葱包裹整个请求</li>
 *   <li>loadSession —— 加载会话状态和历史消息，检测待解决介入</li>
 *   <li>injectSystemMessage —— 在消息列表头部注入系统提示</li>
 *   <li>buildLoopCtx —— 通过 LoopContextFactory 构建循环上下文</li>
 *   <li>execute —— 通过 SessionGate 排队后由 LoopExecutor 执行</li>
 * </ol>
 *
 * <p>流式为 canonical，非流式通过 collectList + assembleResponseFromChunks 派生。
 */
public class RequestPipeline {

    /** 循环执行器，驱动 ReAct 循环 */
    private final LoopExecutor loopExecutor;
    /** 状态存储，用于会话/检查点持久化 */
    private final AgentStateStore stateStore;
    /** AroundHook 链，以洋葱模式包裹整个请求 */
    private final AroundHookChain aroundHookChain;
    /** 执行配置（超时、温度等） */
    private final AgentExecutionConfig execConfig;
    /** Agent 名称 */
    private final String agentName;
    /** 系统提示消息 */
    private final String systemMessage;
    /** 当前活跃的请求上下文（按 requestId 索引） */
    private final ConcurrentHashMap<String, LoopContext> activeRequests;
    /** 会话级串行化门控 */
    private final SessionGate sessionGate;
    /** 介入存储，用于检查点恢复时查询介入状态 */
    private final InterventionStore interventionStore;

    /**
     * 构建请求管线。
     *
     * @param loopExecutor      循环执行器
     * @param stateStore        状态存储
     * @param aroundHookChain   AroundHook 链
     * @param execConfig        执行配置
     * @param agentName         Agent 名称
     * @param systemMessage     系统提示消息
     * @param interventionStore 介入存储
     */
    public RequestPipeline(LoopExecutor loopExecutor, AgentStateStore stateStore,
                            AroundHookChain aroundHookChain, AgentExecutionConfig execConfig,
                            String agentName, String systemMessage,
                            InterventionStore interventionStore, SessionGate sessionGate) {
        this.loopExecutor = loopExecutor;
        this.stateStore = stateStore;
        this.aroundHookChain = aroundHookChain;
        this.execConfig = execConfig;
        this.agentName = agentName;
        this.systemMessage = systemMessage;
        this.activeRequests = new ConcurrentHashMap<>();
        this.sessionGate = sessionGate;
        this.interventionStore = interventionStore;
    }

    /**
     * 流式执行 —— canonical 实现。
     *
     * <p>完整流水线：解析上下文 → AroundHook 包裹 → 加载会话 → 注入系统消息 →
     * 构建 LoopContext → SessionGate 排队 → LoopExecutor.runStream。
     *
     * @param messages 用户消息列表
     * @param rtCtx    运行时上下文（可为 null，从 Reactor Context 回退）
     * @return 流式响应分块
     */
    public Flux<ChatStreamChunk> executeStream(List<Msg> messages, RuntimeContext rtCtx) {
        return Flux.deferContextual(ctxView -> {
            RuntimeContext ctx = resolveContext(ctxView, rtCtx);
            HookContext callHc = HookContext.from(ctx, 0);
            GenerateOptions opts = resolveOptions();

            return aroundHookChain.aroundCallStream(new HookEvent(null), callHc,
                    e -> loadSessionAndHistory(ctx, messages)
                            .flatMapMany(result -> injectSystemMessage(result.messages)
                                    .flatMapMany(Flux::just)
                                    .map(m -> new LoadAndMessages(m, result)))
                            .concatMap(lm -> {
                                LoopContext loopCtx = LoopContextFactory.create(
                                        agentName, ctx, lm.messages, opts, execConfig, true);
                                if (lm.result.interventionId != null) {
                                    loopCtx.getInterventionState().setInterventionId(lm.result.interventionId);
                                    loopCtx.getInterventionState().setInterventionType(lm.result.interventionType);
                                    loopCtx.getInterventionState().setPausedToolArgs(lm.result.pausedToolArgs);
                                }
                                return Flux.using(
                                        () -> {
                                            activeRequests.put(loopCtx.getRequestId(), loopCtx);
                                            return loopCtx;
                                        },
                                        lc -> {
                                            Flux<ChatStreamChunk> inner = loopExecutor.runStream(lc);
                                            long timeout = execConfig.getTotalTimeoutMs();
                                            if (timeout > 0) inner = inner.timeout(Duration.ofMillis(timeout));
                                            return sessionGate.enqueueStream(lc.getSessionId(), inner);
                                        },
                                        lc -> activeRequests.remove(lc.getRequestId())
                                );
                            })
                            .contextWrite(c -> writeContext(c, ctx)));
        });
    }

    /**
     * 非流式执行 —— 从流式派生。
     *
     * <p>流水线与 executeStream 相同，最终通过 collectList + assembleResponseFromChunks
     * 将流式分块组装为单个 ChatResponse。
     *
     * @param messages 用户消息列表
     * @param rtCtx    运行时上下文
     * @return 聊天响应
     */
    public Mono<ChatResponse> execute(List<Msg> messages, RuntimeContext rtCtx) {
        return Mono.deferContextual(ctxView -> {
            final RuntimeContext ctx = resolveContext(ctxView, rtCtx);
            HookContext callHc = HookContext.from(ctx, 0);
            return aroundHookChain.aroundCall(new HookEvent(null), callHc,
                    e -> loadSessionAndHistory(ctx, messages)
                            .flatMap(result -> injectSystemMessage(result.messages)
                                    .map(m -> new LoadAndMessages(m, result)))
                            .flatMap(lm -> {
                                LoopContext loopCtx = LoopContextFactory.create(
                                        agentName, ctx, lm.messages, resolveOptions(), execConfig, false);
                                if (lm.result.interventionId != null) {
                                    loopCtx.getInterventionState().setInterventionId(lm.result.interventionId);
                                    loopCtx.getInterventionState().setInterventionType(lm.result.interventionType);
                                    loopCtx.getInterventionState().setPausedToolArgs(lm.result.pausedToolArgs);
                                }
                                return Mono.using(
                                        () -> {
                                            activeRequests.put(loopCtx.getRequestId(), loopCtx);
                                            return loopCtx;
                                        },
                                        lc -> {
                                            Mono<ChatResponse> exec = loopExecutor.run(lc);
                                            long timeout = execConfig.getTotalTimeoutMs();
                                            if (timeout > 0) exec = exec.timeout(Duration.ofMillis(timeout));
                                            return sessionGate.enqueue(lc.getSessionId(), exec);
                                        },
                                        lc -> activeRequests.remove(lc.getRequestId())
                                );
                            })
                        .map(resp -> { e.setPayload(EventPayload.RESPONSE, resp); return e; }))
                    .map(e -> (ChatResponse) e.getPayload(EventPayload.RESPONSE))
                    .contextWrite(c -> writeContext(c, ctx));
        });
    }

    /**
     * 中断所有活跃请求。
     */
    public void interrupt() {
        for (LoopContext ctx : activeRequests.values()) {
            ctx.interrupt();
        }
    }

    /**
     * 按会话 ID 中断指定会话的请求。
     *
     * @param sessionId 会话标识
     */
    public void interruptBySession(String sessionId) {
        for (LoopContext ctx : activeRequests.values()) {
            if (sessionId != null && sessionId.equals(ctx.getSessionId())) {
                ctx.interrupt();
            }
        }
    }

    /** @return 是否有活跃请求 */
    public boolean isRunning() { return !activeRequests.isEmpty(); }
    /** @return 当前活跃请求映射 */
    public ConcurrentHashMap<String, LoopContext> getActiveRequests() { return activeRequests; }
    /** 清空所有活跃请求 */
    public void shutdown() { activeRequests.clear(); }

    /**
     * 从 Reactor Context 或显式参数解析运行时上下文。
     *
     * @param ctxView Reactor 上下文视图
     * @param rtCtx   显式传入的上下文（优先）
     * @return 解析后的运行时上下文
     */
    private RuntimeContext resolveContext(reactor.util.context.ContextView ctxView, RuntimeContext rtCtx) {
        if (rtCtx != null) return rtCtx;
        String requestId = ctxView.getOrDefault(RuntimeCtx.REQUEST_ID, (String) null);
        String tenantId = ctxView.getOrDefault(RuntimeCtx.TENANT_ID, (String) null);
        String userId = ctxView.getOrDefault(RuntimeCtx.USER_ID, (String) null);
        String sessionId = ctxView.getOrDefault(RuntimeCtx.SESSION_ID, (String) null);
        Map<String, Object> attributes = ctxView.getOrDefault(RuntimeCtx.ATTRIBUTES, (Map<String, Object>) null);
        return new RuntimeContext(requestId, tenantId, userId, sessionId, agentName, attributes);
    }

    /**
     * 将运行时上下文写入 Reactor Context 供子 Agent 传播。
     *
     * @param c   当前 Reactor Context
     * @param ctx 运行时上下文
     * @return 更新后的 Context
     */
    private reactor.util.context.Context writeContext(reactor.util.context.Context c, RuntimeContext ctx) {
        if (ctx.getTenantId() != null) c = c.put(RuntimeCtx.TENANT_ID, ctx.getTenantId());
        if (ctx.getUserId() != null) c = c.put(RuntimeCtx.USER_ID, ctx.getUserId());
        if (ctx.getSessionId() != null) c = c.put(RuntimeCtx.SESSION_ID, ctx.getSessionId());
        if (!ctx.getAttributes().isEmpty()) c = c.put(RuntimeCtx.ATTRIBUTES, ctx.getAttributes());
        return c;
    }

    /**
     * 会话加载结果 —— 消息列表与介入恢复数据的聚合载体。
     */
    private static class SessionLoadResult {
        /** 合并后的消息列表 */
        final List<Msg> messages;
        /** 待解决介入 ID（无则为 null） */
        final String interventionId;
        /** 介入类型 */
        final String interventionType;
        /** 暂停时快照的工具参数 JSON */
        final String pausedToolArgs;

        public SessionLoadResult(List<Msg> messages) {
            this(messages, null, null, null);
        }

        SessionLoadResult(List<Msg> messages, String interventionId,
                          String interventionType, String pausedToolArgs) {
            this.messages = messages;
            this.interventionId = interventionId;
            this.interventionType = interventionType;
            this.pausedToolArgs = pausedToolArgs;
        }
    }

    /**
     * 注入系统消息后的消息与加载结果的聚合载体。
     */
    private static class LoadAndMessages {
        final List<Msg> messages;
        final SessionLoadResult result;
        LoadAndMessages(List<Msg> messages, SessionLoadResult result) {
            this.messages = messages; this.result = result;
        }
    }

    /**
     * 加载会话状态和历史消息，同时检测待解决的介入。
     *
     * <p>流程：根据 sessionId 查找会话 → 加载检查点 → 检测待解决介入 → 合并消息。
     * 若检查点中有 pendingInterventionId，查询 InterventionStore 判断是否已解决，
     * 已解决则携带介入数据供 LoopExecutor 恢复使用。
     *
     * @param ctx      运行时上下文
     * @param messages 当前请求消息
     * @return 会话加载结果（含介入信息）
     */
    private Mono<SessionLoadResult> loadSessionAndHistory(RuntimeContext ctx, List<Msg> messages) {
        String sessionId = ctx.getSessionId();
        if (sessionId == null || stateStore == null)
            return Mono.just(new SessionLoadResult(messages));

        SessionId sid = new SessionId(sessionId);
        String tenantId = ctx.getTenantId() != null ? ctx.getTenantId() : Defaults.TENANT;

        return stateStore.findById(sid)
                .flatMap(session -> stateStore.loadLatestCheckpoint(sessionId)
                        .flatMap(checkpoint -> {
                            if (checkpoint.isShutdownInterrupted()) {
                                checkpoint.setShutdownInterrupted(false);
                                return stateStore.saveCheckpoint(checkpoint).thenReturn(checkpoint);
                            }
                            if (checkpoint.getPendingInterventionId() != null) {
                                return handlePendingIntervention(checkpoint);
                            }
                            return Mono.just(checkpoint);
                        })
                        .flatMap(checkpoint -> {
                            List<Msg> restored = checkpoint.getMessages();
                            if (restored != null && !restored.isEmpty()) {
                                restored.addAll(messages);
                                return Mono.just(new SessionLoadResult(restored,
                                        checkpoint.getPendingInterventionId(),
                                        checkpoint.getInterventionType(),
                                        checkpoint.getPausedToolArgsJson()));
                            }
                            return loadHistory(sessionId, messages).map(
                                    SessionLoadResult::new);
                        })
                        .switchIfEmpty(loadHistory(sessionId, messages).map(
                                SessionLoadResult::new)))
                .switchIfEmpty(
                        stateStore.create(new Session(sid, tenantId, agentName,
                                        SessionState.ACTIVE, null, null, null))
                                .then(Mono.just(new SessionLoadResult(messages))));
    }

    /**
     * 处理检查点中的待解决介入。
     *
     * <p>根据 InterventionStore 中的当前状态：
     * <ul>
     *   <li>PENDING / APPROVED / CLARIFIED / DENIED / EXPIRED —— 保留，供 LoopExecutor 恢复</li>
     *   <li>null —— 清除介入标记（记录已从存储中丢失）</li>
     * </ul>
     *
     * @param checkpoint 含介入标记的检查点
     * @return 更新后的检查点
     */
    private Mono<AgentState> handlePendingIntervention(AgentState checkpoint) {
        String id = checkpoint.getPendingInterventionId();
        InterventionRequest req = interventionStore.getById(id);

        if (req == null) {
            checkpoint.setPendingInterventionId(null);
            checkpoint.setInterventionType(null);
            checkpoint.setPausedToolArgsJson(null);
            return Mono.just(checkpoint);
        }

        // PENDING / APPROVED / CLARIFIED — 保留介入字段，
        // 由 LoopExecutor.runStream → resumeFromIntervention 决定如何处理
        return Mono.just(checkpoint);
    }

    /**
     * 从状态存储加载会话历史消息。
     *
     * @param sessionId 会话标识
     * @param messages  当前请求消息
     * @return 合并后的消息列表
     */
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

    /**
     * 在消息列表头部注入系统提示消息。
     *
     * @param messages 原始消息列表
     * @return 注入后的消息列表
     */
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

    /**
     * 从执行配置解析生成选项。
     *
     * @return 生成选项（温度、最大Token、工具策略）
     */
    private GenerateOptions resolveOptions() {
        return GenerateOptions.builder()
                .temperature(execConfig.getTemperature())
                .maxTokens(execConfig.getMaxTokens())
                .toolChoice(execConfig.getToolChoice())
                .build();
    }
}
