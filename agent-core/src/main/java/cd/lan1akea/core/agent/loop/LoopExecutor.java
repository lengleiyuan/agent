package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.CoreConstants.EventPayload;
import cd.lan1akea.core.CoreConstants.FinishReason;
import cd.lan1akea.core.CoreConstants.HookSource;
import cd.lan1akea.core.CoreConstants.Intervention;
import cd.lan1akea.core.CoreConstants.Logs;
import cd.lan1akea.core.CoreConstants.UI;
import cd.lan1akea.core.CoreConstants.Usage;
import cd.lan1akea.core.exception.HookAbortException;
import cd.lan1akea.core.exception.HumanInterventionException;
import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.intervention.InterventionRequest;
import cd.lan1akea.core.intervention.InterventionStore;
import cd.lan1akea.core.message.*;
import cd.lan1akea.core.metrics.AgentMetrics;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.tool.*;
import cd.lan1akea.core.util.JsonUtils;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * ReAct 循环执行器。
 * 以 executePhase 状态机中枢驱动四阶段循环，流式为 canonical。
 *
 * <p>流程: runStream(Guard) -> executePhase(Reason) -> executePhase(Act)
 *       -> executePhase(Observe) -> executePhase(Guard -> ...)
 * <p>完成信号由 LoopDecisionEngine 通过 ctx.isComplete() 检测并产生 Stop 决策。
 */
public class LoopExecutor {

    /** 日志记录器 */
    private static final Logger log = Logger.getLogger(LoopExecutor.class.getName());

    /** 循环决策引擎，用于评估当前阶段并决定下一步行为 */
    private final LoopDecisionEngine engine;

    /** 模型调用管道，负责与 LLM 交互并获取回复 */
    private final ModelCallPipeline modelPipeline;

    /** 工具调用编排器，负责工具的执行与结果收集 */
    private final ToolCallOrchestrator toolOrchestrator;

    /** Hook 分发器，用于在循环各阶段触发回调 */
    private final HookDispatcher hookDispatcher;

    /** Agent 指标收集器，记录迭代次数等运行时数据 */
    private final AgentMetrics metrics;

    /** 人工介入请求存储器 */
    private final InterventionStore interventionStore;

    /** Token 估算器，用于统计每次模型调用的实际 token 消耗 */
    private final TokenEstimator tokenEstimator;

    /**
     * 构造 ReAct 循环执行器。
     *
     * @param engine             循环决策引擎
     * @param modelPipeline      模型调用管道
     * @param toolOrchestrator   工具调用编排器
     * @param hookDispatcher     Hook 分发器
     * @param metrics            Agent 指标收集器
     * @param interventionStore  介入请求存储器
     * @param tokenEstimator     Token 估算器
     */
    public LoopExecutor(LoopDecisionEngine engine, ModelCallPipeline modelPipeline,
                         ToolCallOrchestrator toolOrchestrator, HookDispatcher hookDispatcher,
                         AgentMetrics metrics, InterventionStore interventionStore,
                         TokenEstimator tokenEstimator) {
        this.engine = engine;
        this.modelPipeline = modelPipeline;
        this.toolOrchestrator = toolOrchestrator;
        this.hookDispatcher = hookDispatcher;
        this.metrics = metrics;
        this.interventionStore = interventionStore;
        this.tokenEstimator = tokenEstimator;
    }

    /**
     * 启动流式 ReAct 循环（canonical 入口）。
     *
     * <p>每次调用首先检查是否需要从介入状态恢复，或处理中断信号；
     * 否则通过决策引擎评估 Guard 阶段，决定停止或进入 Reason/Act 循环。
     *
     * @param ctx 循环上下文
     * @return 流式输出 chunk 序列
     */
    public Flux<ChatStreamChunk> runStream(LoopContext ctx) {
        return Flux.defer(() -> {
            // 检查是否有已解决的介入需要恢复
            if (ctx.getInterventionId() != null && !ctx.isInterrupted()) {
                return resumeFromIntervention(ctx);
            }
            if (ctx.isInterrupted()) {
                return handleInterruptStream(ctx);
            }
            Decision d = engine.evaluate(Phase.guard(), ctx);
            if (d.isStop()) {
                return Flux.just(chunkFromResponse(d.getResponse()));
            }
            if (ctx.getIteration() >= ctx.getMaxIterations()) {
                return dispatchSummarizeHook(ctx);
            }
            return executePhase(d, ctx);
        });
    }

    /**
     * 分发 PRE_SUMMARIZE Hook 并应用内置兜底。
     *
     * <p>Hook 可通过 ReasoningEvent.setBypassMessage() 跳过模型直接返回自定义摘要。
     * 未设置 bypass 时应用内置兜底：禁用工具
     */
    private Flux<ChatStreamChunk> dispatchSummarizeHook(LoopContext ctx) {
        HookContext hc = buildHookContext(ctx);
        ReasoningEvent event = new ReasoningEvent(HookEventType.PRE_SUMMARIZE);
        event.setMessages(ctx.getMessages());

        return hookDispatcher.dispatch(event, hc)
                .flatMapMany(r -> {
                    if (r.isAbort()) {
                        return Flux.error(new HookAbortException(HookSource.HOOK, r.getAbortReason()));
                    }
                    if (event.getBypassMessage() != null) {
                        Msg bypass = event.getBypassMessage();
                        ctx.addMessage(bypass);
                        return Flux.just(ChatStreamChunk.of(bypass.getTextContent(), FinishReason.STOP));
                    }
                    applySummarizeFallback(ctx);
                    return executePhase(Decision.continue_(Phase.reason()), ctx);
                });
    }

    /**
     * 内置兜底：禁用工具，其余保持模型配置。
     */
    private void applySummarizeFallback(LoopContext ctx) {
        GenerateOptions opts = ctx.getGenerateOptions();
        ctx.setGenerateOptions(GenerateOptions.builder()
                .temperature(opts.getTemperature())
                .maxTokens(opts.getMaxTokens())
                .toolChoice(ToolChoicePolicy.NONE)
                .build());
    }

    /**
     * 从人工介入状态恢复执行。
     *
     * <p>检查介入请求的当前状态并执行对应恢复策略：
     * <ul>
     *   <li>APPROVED — 以原参数重新执行被暂停的工具</li>
     *   <li>CLARIFIED — 以修正参数重新执行被暂停的工具</li>
     *   <li>DENIED — 向 Agent 注入拒绝消息后继续循环</li>
     *   <li>EXPIRED — 清除介入状态，正常继续执行</li>
     *   <li>PENDING — 返回等待中的干预 chunk</li>
     * </ul>
     *
     * @param ctx 循环上下文
     * @return 恢复后的流式 chunk 序列
     */
    private Flux<ChatStreamChunk> resumeFromIntervention(LoopContext ctx) {
        String id = ctx.getInterventionId();
        InterventionRequest req = interventionStore.getById(id);
        if (req == null || req.getStatus() == InterventionRequest.Status.EXPIRED) {
            ctx.setInterventionId(null);
            ctx.setInterventionType(null);
            return runStream(ctx); // 过期，继续正常执行
        }
        switch (req.getStatus()) {
            case APPROVED:
                return resumeApprovedTool(ctx, req);
            case CLARIFIED:
                return resumeClarifiedTool(ctx, req);
            case DENIED:
                ctx.setInterventionId(null);
                ctx.setInterventionType(null);
                ctx.addMessage(SystemMessage.of(Intervention.MSG_DENIED));
                return runStream(ctx);
            default:
                return Flux.just(interventionChunk(id, Intervention.MSG_WAITING,
                        req.getType().name(), req.getToolName()));
        }
    }

/**
     * 恢复已批准的介入：以原参数重新执行工具。
     *
     * <p>将暂停时保存的工具参数反序列化，构建 ToolCallContext 并标记为 approved，
     * 通过 {@link ToolCallOrchestrator#executeDirect} 直接执行（跳过审批流程）。
     *
     * @param ctx 循环上下文
     * @param req 已批准的介入请求（含原工具参数）
     * @return 恢复后的流式 chunk 序列
     */
    private Flux<ChatStreamChunk> resumeApprovedTool(LoopContext ctx, InterventionRequest req) {
        String argsJson = ctx.getPausedToolArgs();
        Map<String, Object> args = argsJson != null
                ? JsonUtils.safeParseMap(argsJson) : Map.of();
        return resumeToolWithArgs(ctx, req, args);
    }

    /**
     * 恢复已澄清的介入：以修正参数重新执行工具。
     *
     * <p>使用介入请求中保存的 {@code modifiedArgs} 构建 ToolCallContext，
     * 以人工修正后的参数重新执行被暂停的工具。
     *
     * @param ctx 循环上下文
     * @param req 已澄清的介入请求（含修正参数）
     * @return 恢复后的流式 chunk 序列
     */
    private Flux<ChatStreamChunk> resumeClarifiedTool(LoopContext ctx, InterventionRequest req) {
        Map<String, Object> modified = req.getModifiedArgs() != null
                ? req.getModifiedArgs() : Map.of();
        return resumeToolWithArgs(ctx, req, modified);
    }

    private Flux<ChatStreamChunk> resumeToolWithArgs(LoopContext ctx, InterventionRequest req,
                                                      Map<String, Object> args) {
        ToolCallContext callParam = ToolCallContext.builder()
                .callId(Intervention.RESUME_CALL_PREFIX + req.getInterventionId())
                .toolName(req.getToolName())
                .arguments(args)
                .tenantId(ctx.getTenantId())
                .userId(ctx.getUserId())
                .sessionId(ctx.getSessionId())
                .attributes(ctx.getAttributes())
                .build();
        callParam.setApproved(true);

        ctx.setInterventionId(null);
        ctx.setInterventionType(null);
        ctx.setPausedToolArgs(null);

        return toolOrchestrator.executeDirect(callParam, ctx)
                .flatMapMany(result -> {
                    ChatStreamChunk chunk = chunkFromToolResult(result);
                    appendSingleToolResult(ctx, result.withCallId(callParam.getCallId()), callParam.getCallId());
                    return Flux.just(chunk);
                })
                .concatWith(Flux.defer(() -> executePhase(Decision.continue_(Phase.observe()), ctx)));
    }

    /**
     * 追加单个工具执行结果到上下文消息列表。
     *
     * <p>将工具执行结果以 TOOL 角色的消息添加到上下文中。
     * assistant 消息已由 executeReason 统一追加。
     *
     * @param ctx    循环上下文
     * @param result 工具执行结果
     * @param callId 工具调用 ID
     */
    private void appendSingleToolResult(LoopContext ctx, ToolResult result, String callId) {
        ctx.addMessage(Msg.builder(MsgRole.TOOL)
                .addToolResult(callId,
                        result.isSuccess() ? result.getContent()
                                : UI.TOOL_ERROR_PREFIX + result.getErrorMessage(),
                        !result.isSuccess())
                .build());
    }

    /**
     * 执行推理阶段：调用模型获取回复。
     *
     * <p>流式收集模型分块 → 组装 ChatResponse → 设置 lastResponse 和 token。
     * 将 assistant 消息（含 tool_use blocks）追加到 ctx。
     * 不检查工具调用、不决定下一阶段 —— 由引擎 evaluator 负责。
     *
     * @param ctx 循环上下文
     * @return 模型推理的流式分块
     */
    private Flux<ChatStreamChunk> executeReason(LoopContext ctx) {
        List<ChatStreamChunk> buffer = new ArrayList<>();
        return modelPipeline.executeStream(ctx)
                .doOnNext(buffer::add)
                .concatWith(Flux.defer(() -> {
                    ChatResponse resp = ModelCallPipeline.assembleResponseFromChunks(buffer);
                    if (resp == null) return Flux.empty();

                    ctx.setLastResponse(resp);
                    if (resp.getUsage() != null) {
                        ctx.addTokens(resp.getUsage().getTotalTokens());
                    }
                    Msg assistantMsg = resp.getMessage();
                    if (assistantMsg != null) {
                        ctx.addMessage(assistantMsg);
                    }

                    // 统计真实 token 用量，作为 usage chunk 下发前端
                    int promptTokens = tokenEstimator.estimate(ctx.getMessages());
                    int completionTokens = assistantMsg != null
                            ? tokenEstimator.estimate(assistantMsg) : 0;
                    Map<String, Object> usage = new LinkedHashMap<>();
                    usage.put(Usage.PROMPT_TOKENS, promptTokens);
                    usage.put(Usage.COMPLETION_TOKENS, completionTokens);
                    return Flux.just(ChatStreamChunk.builder()
                            .delta(JsonUtils.toCompactJson(usage))
                            .type(Usage.CHUNK_TYPE)
                            .build());
                }));
    }

    /**
     * 执行行动阶段：并行执行工具调用并收集结果。
     *
     * <p>记录指标（iteration 此时尚未递增，使用 +1），执行工具，
     * 只追加 tool_result 消息（assistant 消息已由 executeReason 追加），
     * 应用 backoff。不递增 iteration、不分发 after-iteration hook、
     * 不调用 runStream —— 由 executePhase 链式进入 Observe。
     *
     * @param ctx       循环上下文
     * @param toolCalls 待执行的工具调用列表
     * @return 流式输出 chunk 序列
     */
    private Flux<ChatStreamChunk> executeAct(LoopContext ctx, List<ToolUseBlock> toolCalls) {
        metrics.recordIteration(ctx.getAgentName(), ctx.getSessionId(),
                ctx.getIteration() + 1, toolCalls.size());

        List<ToolResult> results = new java.util.concurrent.CopyOnWriteArrayList<>();

        return Flux.fromIterable(toolCalls)
                .flatMap(tc -> toolOrchestrator.execute(tc, ctx)
                        .doOnNext(results::add)
                        .map(this::chunkFromToolResult))
                .onErrorResume(e -> handleToolError(e, ctx, results))
                .concatWith(Flux.defer(() -> {
                    appendToolResults(ctx, results);
                    return Mono.delay(Duration.ofMillis(ctx.getBackoffMs())).flux()
                            .thenMany(Flux.<ChatStreamChunk>empty());
                }));
    }

    /**
     * 执行观察阶段：递增迭代并分发 after-iteration Hook。
     *
     * <p>每次迭代恰好调用一次，由 executePhase 链式进入。
     * 触发 AFTER_ITERATION Hook → SessionPersistenceHook 持久化。
     *
     * @param ctx 循环上下文
     * @return 完成信号
     */
    private Flux<ChatStreamChunk> executeObserve(LoopContext ctx) {
        ctx.setIteration(ctx.getIteration() + 1);
        return dispatchAfterIteration(ctx)
                .thenMany(Flux.<ChatStreamChunk>empty());
    }

    /**
     * 状态机路由中枢。
     *
     * <p>根据引擎决策执行对应阶段，阶段完成后回访引擎获取下一决策，
     * 通过 concatWith + defer 实现订阅级递归（非调用栈递归）。
     * Stop 时返回空 Flux 结束递归。
     *
     * <p>介入中断检测：Reason 阶段前检查 ctx.isInterrupted()，
     * 中断时跳过模型调用但允许 Observe 完成持久化后再终止递归。
     *
     * @param decision 引擎决策
     * @param ctx      循环上下文
     * @return 流式输出 chunk 序列
     */
    private Flux<ChatStreamChunk> executePhase(Decision decision, LoopContext ctx) {
        if (decision.isStop()) {
            return Flux.empty();
        }
        Phase next = decision.getNextPhase();

        // 中断时跳过推理阶段，避免不必要的模型调用
        // Observe 不受影响，确保持久化在中断前完成
        if (next.isReason() && ctx.isInterrupted()) {
            return Flux.empty();
        }

        Flux<ChatStreamChunk> phaseFlux;
        if (next.isReason()) {
            phaseFlux = executeReason(ctx);
        } else if (next.isAct()) {
            phaseFlux = executeAct(ctx, next.getToolCalls());
        } else if (next.isObserve()) {
            phaseFlux = executeObserve(ctx);
        } else {
            phaseFlux = Flux.empty();
        }
        return phaseFlux.concatWith(Flux.defer(() -> {
            Decision nextDecision = engine.evaluate(next, ctx);
            return executePhase(nextDecision, ctx);
        }));
    }

    /**
     * 处理工具执行过程中的异常。
     *
     * <p>根据异常类型分别处理：
     * <ul>
     *   <li>{@link HumanInterventionException} — 可恢复时进入介入流程，不可恢复时透传</li>
     *   <li>{@link ToolSuspendException} — 转换为 HumanInterventionException 后进入介入流程</li>
     *   <li>其他异常 — 构造失败结果后继续循环</li>
     * </ul>
     *
     * @param e       异常
     * @param ctx     循环上下文
     * @param results 已收集的工具结果列表
     * @return 处理后的流式 chunk 序列
     */
    private Flux<ChatStreamChunk> handleToolError(Throwable e, LoopContext ctx,
                                                   List<ToolResult> results) {
        if (e instanceof HumanInterventionException hie) {
            if (!hie.isResumable()) return Flux.error(e);
            return handleIntervention(hie, ctx);
        }
        if (e instanceof ToolSuspendException tse) {
            return handleLegacySuspension(tse, ctx);
        }
        // Other errors: add failure result and continue
        ToolResult failure = ToolResult.failure(UI.TOOL_EXEC_ERROR + e.getMessage());
        results.add(failure);
        return Flux.just(chunkFromToolResult(failure));
    }

    /**
     * 处理人工介入异常，创建介入请求并暂停循环。
     *
     * <p>将 {@link HumanInterventionException} 转换为 {@link InterventionRequest} 并持久化，
     * 设置上下文的中断状态，返回包含介入信息的 chunk 给前端。
     *
     * @param e   HumanInterventionException 实例
     * @param ctx 循环上下文
     * @return 包含介入信息的 chunk 序列
     */
    private Flux<ChatStreamChunk> handleIntervention(HumanInterventionException e, LoopContext ctx) {
        InterventionRequest req = InterventionRequest.builder()
                .type(toInterventionType(e.getType()))
                .sessionId(ctx.getSessionId())
                .requestId(ctx.getRequestId())
                .tenantId(ctx.getTenantId())
                .agentName(ctx.getAgentName())
                .toolName(e.getToolName())
                .question(e.getReason())
                .toolArgs(e.getCallParam() != null ? e.getCallParam().getArgumentsMap() : null)
                .recentMessages(truncateMessages(ctx.getMessages()))
                .build();

        String id = interventionStore.create(req);
        ctx.setInterventionId(id);
        ctx.setInterventionType(e.getType().name());
        if (e.getCallParam() != null) {
            ctx.setPausedToolArgs(JsonUtils.toCompactJson(e.getCallParam().getArgumentsMap()));
        }
        ctx.interrupt();

        return Flux.just(interventionChunk(id, e.getReason(), e.getType().name(), e.getToolName()));
    }

    /**
     * 处理旧的 {@link ToolSuspendException}（兼容老版本）。
     *
     * <p>将 ToolSuspendException 转换为 HumanInterventionException 后，
     * 委托给 {@link #handleIntervention} 统一处理。
     *
     * @param e   ToolSuspendException 实例
     * @param ctx 循环上下文
     * @return 包含介入信息的 chunk 序列
     */
    private Flux<ChatStreamChunk> handleLegacySuspension(ToolSuspendException e, LoopContext ctx) {
        HumanInterventionException hie = HumanInterventionException.approval(
                e.getBypassKey(), e.getQuestion(), null);
        return handleIntervention(hie, ctx);
    }

    /**
     * 将 {@link HumanInterventionException.Type} 转换为 {@link InterventionRequest.Type}。
     *
     * @param t 人工介入异常类型
     * @return 对应的介入请求类型
     */
    private static InterventionRequest.Type toInterventionType(HumanInterventionException.Type t) {
        switch (t) {
            case TOOL_APPROVAL: return InterventionRequest.Type.TOOL_APPROVAL;
            case TOOL_CLARIFY: return InterventionRequest.Type.TOOL_CLARIFY;
            default: return InterventionRequest.Type.BUSINESS_PAUSE;
        }
    }

    /**
     * 截断消息列表至最近的 {@value Intervention#RECENT_MSG_LIMIT} 条。
     *
     * <p>在创建介入请求时使用，仅保存最近的上下文消息以减小持久化开销。
     *
     * @param messages 原始消息列表
     * @return 截断后的消息列表
     */
    private List<Msg> truncateMessages(List<Msg> messages) {
        int size = messages.size();
        int from = Math.max(0, size - Intervention.RECENT_MSG_LIMIT);
        return new ArrayList<>(messages.subList(from, size));
    }

    /**
     * 从工具结果构建文本类型的消息 chunk。
     *
     * @param result 工具执行结果
     * @return 消息 chunk
     */
    private ChatStreamChunk chunkFromToolResult(ToolResult result) {
        String content = result.isSuccess()
                ? result.getContent()
                : UI.TOOL_ERROR_PREFIX + result.getErrorMessage();
        return ChatStreamChunk.builder()
                .delta(content).type(ChatStreamChunk.TYPE_TEXT).build();
    }

    /**
     * 构建一个干预信号 chunk，通知前端需要人工介入。
     *
     * <p>该 chunk 包含介入 ID、问题描述、介入类型和工具名称，
     * 并设置 chunk 类型为 {@value Intervention#CHUNK_TYPE}，
     * payload type 为 {@value Intervention#PAYLOAD_TYPE}，
     * finish_reason 为 {@value Intervention#FINISH_REASON} 以标记流式结束。
     *
     * @param id               介入记录 ID
     * @param question         审批问题描述
     * @param interventionType 介入类型名称
     * @param toolName         工具名称
     * @return 干预信号 chunk
     */
    private ChatStreamChunk interventionChunk(String id, String question,
                                               String interventionType, String toolName) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(EventPayload.TYPE, Intervention.PAYLOAD_TYPE);
        payload.put(EventPayload.INTERVENTION_ID, id);
        payload.put(EventPayload.QUESTION, question);
        payload.put(EventPayload.INTERVENTION_TYPE, interventionType);
        payload.put(EventPayload.TOOL_NAME, toolName);
        return ChatStreamChunk.builder()
                .delta(JsonUtils.toCompactJson(payload))
                .type(Intervention.CHUNK_TYPE)
                .finishReason(Intervention.FINISH_REASON)
                .build();
    }

    /**
     * 非流式入口：等待流式循环完成，返回最终模型响应。
     *
     * <p>收集所有流式 chunk 后返回上下文中保存的最后一次模型响应。
     * 如果上下文中没有响应记录，返回一个空响应（包含零 token 用量和 "empty" 结束原因）。
     *
     * @param ctx 循环上下文
     * @return 最终模型响应（Mono）
     */
    public Mono<ChatResponse> run(LoopContext ctx) {
        return runStream(ctx)
                .then(Mono.fromSupplier(() -> {
                    ChatResponse resp = ctx.getLastResponse();
                    if (resp != null) return resp;
                    return new ChatResponse(null, new ChatUsage(0, 0), Intervention.EMPTY_REASON, "");
                }));
    }

    /**
     * 处理中断流：分发中断事件 Hook，根据结果决定中止或恢复。
     *
     * <p>通过 {@link HookDispatcher} 分发 {@link InterruptEvent}，
     * 如果 hook 返回 abort 则生成中断结束 chunk；
     * 否则注入反馈消息后恢复循环，或返回已中断原因。
     *
     * @param ctx 循环上下文
     * @return 中断处理后的流式 chunk 序列
     */
    private Flux<ChatStreamChunk> handleInterruptStream(LoopContext ctx) {
        Msg feedback = ctx.getFeedbackMsg();
        HookContext hc = buildHookContext(ctx);
        InterruptEvent ie = new InterruptEvent(
                feedback != null ? feedback.getTextContent() : UI.INTERRUPT_EXTERNAL, null);

        return hookDispatcher.dispatch(ie, hc)
                .flatMapMany(r -> {
                    if (r.isAbort()) {
                        return Flux.just(ChatStreamChunk.of(
                                UI.INTERRUPT_STREAM_PREFIX + r.getAbortReason()
                                        + UI.INTERRUPT_SUFFIX,
                                FinishReason.INTERRUPTED));
                    }
                    if (feedback != null) {
                        ctx.addMessage(feedback);
                        ctx.clearInterrupt();
                        return runStream(ctx);
                    }
                    String reason = ctx.getLastResponse() != null
                            ? ctx.getLastResponse().getMessage().getTextContent()
                            : UI.INTERRUPT_EXEC;
                    return Flux.just(ChatStreamChunk.of(reason, FinishReason.INTERRUPTED));
                });
    }

    /**
     * 批量追加工具执行结果到上下文消息列表。
     *
     * <p>只追加 TOOL 角色的 tool result 消息。
     * assistant 消息已由 executeReason 统一追加。
     *
     * @param ctx     循环上下文
     * @param results 工具执行结果列表
     */
    private void appendToolResults(LoopContext ctx, List<ToolResult> results) {
        for (ToolResult r : results) {
            String callId = r.getCallId();
            if (callId == null) continue;
            ctx.addMessage(Msg.builder(MsgRole.TOOL)
                    .addToolResult(callId,
                            r.isSuccess() ? r.getContent()
                                    : UI.TOOL_ERROR_PREFIX + r.getErrorMessage(),
                            !r.isSuccess())
                    .build());
        }
    }

    /**
     * 分发迭代后 Hook 事件。
     *
     * <p>在每次迭代（Reason 或 Act 阶段）完成后触发 {@link HookEventType#AFTER_ITERATION} 事件，
     * 允许外部监听器感知循环进度。事件分发失败时仅记录警告，不影响循环继续。
     *
     * @param ctx 循环上下文
     * @return 分发完成信号
     */
    private Mono<Void> dispatchAfterIteration(LoopContext ctx) {
        HookContext hc = buildHookContext(ctx);
        HookEvent event = new HookEvent(HookEventType.AFTER_ITERATION);
        event.setPayload(EventPayload.LOOP_CONTEXT, ctx);
        return hookDispatcher.dispatch(event, hc)
                .onErrorResume(e -> {
                    log.warning(Logs.AFTER_ITERATION_FAILED
                            + ctx.getRequestId() + Logs.ERR_DETAIL + e.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    /**
     * 构建 Hook 分发的上下文对象。
     *
     * @param ctx 循环上下文
     * @return Hook 上下文
     */
    private HookContext buildHookContext(LoopContext ctx) {
        return ctx.toHookContext();
    }

    /**
     * 从 {@link ChatResponse} 构建文本类型的输出 chunk。
     *
     * @param resp 模型回复
     * @return 输出 chunk
     */
    private static ChatStreamChunk chunkFromResponse(ChatResponse resp) {
        if (resp == null || resp.getMessage() == null) return ChatStreamChunk.of("", "");
        return ChatStreamChunk.builder()
                .delta(resp.getMessage() != null ? resp.getMessage().getTextContent() : "")
                .finishReason(resp.getFinishReason())
                .build();
    }
}
