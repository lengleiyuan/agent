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
            return aroundHookChain.aroundCallStream(new HookEvent(null), HookContext.from(ctx, 0),
                    e -> prepareMessages(ctx, messages)
                            .flatMapMany(this::executeAsStream)
                            .contextWrite(c -> writeContext(c, ctx)));
        });
    }

    /**
     * 非流式执行 —— 从流式派生。
     *
     * <p>直接调用 {@link #executeStream}，通过 collectList 收集全部分块后
     * 调用 {@link ModelCallPipeline#assembleResponseFromChunks} 组装为单个 ChatResponse。
     *
     * @param messages 用户消息列表
     * @param rtCtx    运行时上下文
     * @return 聊天响应
     */
    public Mono<ChatResponse> execute(List<Msg> messages, RuntimeContext rtCtx) {
        return executeStream(messages, rtCtx)
                .collectList()
                .map(ModelCallPipeline::assembleResponseFromChunks);
    }

    /**
     * 加载会话状态和历史消息，注入系统提示，合并为 LoadAndMessages。
     *
     * <p>串联 loadSessionAndHistory → injectSystemMessage 两个步骤，
     * 产出一个携带完整消息列表、介入恢复数据和运行时上下文的聚合对象。
     *
     * @param ctx      运行时上下文
     * @param messages 当前请求消息
     * @return 聚合后的 LoadAndMessages
     */
    private Mono<LoadAndMessages> prepareMessages(RuntimeContext ctx, List<Msg> messages) {
        return loadSessionAndHistory(ctx, messages)
                .flatMap(result -> injectSystemMessage(result.messages)
                        .map(m -> new LoadAndMessages(m, result, ctx)));
    }

    /**
     * 创建 LoopContext → 应用介入状态 → 以 Flux.using 执行流式循环。
     *
     * <p>LoopContext 通过 {@link LoopContextFactory#create} 构建，stream 标志为 true。
     * 执行前通过 {@link #trackActive} 注册到活跃请求表，
     * 执行后通过 {@link #untrackActive} 清理，保证异常/取消场景也能正确移除。
     *
     * @param lm 包含消息列表、会话加载结果和运行时上下文的聚合对象
     * @return 流式响应分块 Flux
     */
    private Flux<ChatStreamChunk> executeAsStream(LoadAndMessages lm) {
        LoopContext loopCtx = LoopContextFactory.create(
                agentName, lm.ctx, lm.messages, resolveOptions(), execConfig, true);
        applyInterventionState(lm.result, loopCtx);
        return Flux.using(
                () -> trackActive(loopCtx),
                lc -> withTimeoutAndGate(lc, loopExecutor.runStream(lc)),
                this::untrackActive);
    }

    /**
     * 将 SessionLoadResult 中的介入恢复数据应用到 LoopContext。
     *
     * <p>仅在 interventionId 非 null 时写入三个字段：
     * interventionId、interventionType、pausedToolArgs。
     * 供 LoopExecutor 在 runStream 入口检测并恢复暂停的执行。
     *
     * @param result   会话加载结果（含介入标记）
     * @param loopCtx  待写入的循环上下文
     */
    private void applyInterventionState(SessionLoadResult result, LoopContext loopCtx) {
        if (result.interventionId != null) {
            loopCtx.getInterventionState().setInterventionId(result.interventionId);
            loopCtx.getInterventionState().setInterventionType(result.interventionType);
            loopCtx.getInterventionState().setPausedToolArgs(result.pausedToolArgs);
        }
    }

    /**
     * 注册活跃请求到 ConcurrentHashMap，返回 LoopContext 供 Flux.using / Mono.using 使用。
     *
     * <p>以 requestId 为 key，支持外部中断（interrupt / interruptBySession）
     * 通过查找此表定位目标 LoopContext。
     *
     * @param lc 循环上下文
     * @return 原值透传
     */
    private LoopContext trackActive(LoopContext lc) {
        activeRequests.put(lc.getRequestId(), lc);
        return lc;
    }

    /**
     * 从活跃请求表中移除，与 {@link #trackActive} 配对。
     *
     * <p>作为 Flux.using / Mono.using 的清理回调，确保异常/取消路径也能正确清理。
     *
     * @param lc 循环上下文
     */
    private void untrackActive(LoopContext lc) {
        activeRequests.remove(lc.getRequestId());
    }

    /**
     * 对 Flux 执行体应用总超时和会话门控串行化。
     *
     * <p>先根据 execConfig.totalTimeoutMs 设置超时，
     * 再通过 sessionGate.enqueueStream 保证同一 session 的 FIFO 执行。
     *
     * @param lc    循环上下文（含 sessionId）
     * @param inner 原始执行体
     * @return 包装后的 Flux
     */
    private Flux<ChatStreamChunk> withTimeoutAndGate(LoopContext lc, Flux<ChatStreamChunk> inner) {
        long timeout = execConfig.getTotalTimeoutMs();
        if (timeout > 0) inner = inner.timeout(Duration.ofMillis(timeout));
        return sessionGate.enqueueStream(lc.getSessionId(), inner);
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

    /**
     * 判断当前是否有正在执行的请求。
     *
     * @return true 表示有活跃请求
     */
    public boolean isRunning() { return !activeRequests.isEmpty(); }

    /**
     * 获取当前活跃请求映射表。
     *
     * <p>供 ReActAgent 遍历并进行中断操作。
     * key 为 requestId，value 为 LoopContext。
     *
     * @return 活跃请求映射（不可变视图由调用方自行处理）
     */
    public ConcurrentHashMap<String, LoopContext> getActiveRequests() { return activeRequests; }

    /**
     * 关闭管线，清空所有活跃请求记录。
     *
     * <p>不等待执行中的请求完成。Agent shutdown 时调用。
     */
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
        final RuntimeContext ctx;
        LoadAndMessages(List<Msg> messages, SessionLoadResult result, RuntimeContext ctx) {
            this.messages = messages; this.result = result; this.ctx = ctx;
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
                        .flatMap(this::resolveCheckpoint)
                        .flatMap(cp -> restoreFromCheckpoint(cp, messages, sessionId))
                        .switchIfEmpty(loadHistory(sessionId, messages).map(SessionLoadResult::new)))
                .switchIfEmpty(
                        stateStore.create(new Session(sid, tenantId, agentName, SessionState.ACTIVE))
                                .then(Mono.just(new SessionLoadResult(messages))));
    }

    /**
     * 处理检查点中的异常状态：shutdown 中断标记和待解决介入。
     *
     * <p>shutdown 中断：清除标记后保存，防止下次请求再次触发。
     * 待解决介入：委托 {@link #handlePendingIntervention} 查询当前状态。
     * 正常状态：原样透传。
     *
     * @param checkpoint 原始检查点
     * @return 规范化后的检查点
     */
    private Mono<AgentState> resolveCheckpoint(AgentState checkpoint) {
        if (checkpoint.isShutdownInterrupted()) {
            checkpoint.setShutdownInterrupted(false);
            return stateStore.saveCheckpoint(checkpoint).thenReturn(checkpoint);
        }
        if (checkpoint.getPendingInterventionId() != null) {
            return handlePendingIntervention(checkpoint);
        }
        return Mono.just(checkpoint);
    }

    /**
     * 从检查点恢复消息列表，构建 SessionLoadResult。
     *
     * <p>检查点中有完整消息 → 拼接新消息 → 附带介入恢复标记。
     * 检查点中无消息 → 回退到 loadHistory。
     *
     * @param checkpoint 规范化后的检查点
     * @param messages   当前请求消息
     * @param sessionId  会话标识
     * @return 会话加载结果
     */
    private Mono<SessionLoadResult> restoreFromCheckpoint(AgentState checkpoint,
                                                           List<Msg> messages, String sessionId) {
        List<Msg> restored = checkpoint.getMessages();
        if (restored != null && !restored.isEmpty()) {
            restored.addAll(messages);
            return Mono.just(new SessionLoadResult(restored,
                    checkpoint.getPendingInterventionId(),
                    checkpoint.getInterventionType(),
                    checkpoint.getPausedToolArgsJson()));
        }
        return loadHistory(sessionId, messages).map(SessionLoadResult::new);
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
