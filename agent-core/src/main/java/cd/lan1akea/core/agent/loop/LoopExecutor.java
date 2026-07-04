package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.CoreConstants.EventPayload;
import cd.lan1akea.core.CoreConstants.FinishReason;
import cd.lan1akea.core.CoreConstants.Intervention;
import cd.lan1akea.core.CoreConstants.Logs;
import cd.lan1akea.core.CoreConstants.UI;
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
 * 以显式循环替代递归 flatMap，流式为 canonical。
 *
 * 流程: runStream(Guard) -> executeAndContinue(Reason)
 *       -> [no tools: STOP] [has tools: executeAndContinue(Act)]
 *       -> [Act: execute+collect -> runStream(Guard)]
 */
public class LoopExecutor {

    private static final Logger log = Logger.getLogger(LoopExecutor.class.getName());

    private final LoopDecisionEngine engine;
    private final ModelCallPipeline modelPipeline;
    private final ToolCallOrchestrator toolOrchestrator;
    private final HookDispatcher hookDispatcher;
    private final AgentMetrics metrics;
    private final InterventionStore interventionStore;

    public LoopExecutor(LoopDecisionEngine engine, ModelCallPipeline modelPipeline,
                         ToolCallOrchestrator toolOrchestrator, HookDispatcher hookDispatcher,
                         AgentMetrics metrics, InterventionStore interventionStore) {
        this.engine = engine;
        this.modelPipeline = modelPipeline;
        this.toolOrchestrator = toolOrchestrator;
        this.hookDispatcher = hookDispatcher;
        this.metrics = metrics;
        this.interventionStore = interventionStore;
    }

    // ============================================================
    // 流式 — canonical
    // ============================================================

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
            return executeAndContinue(d.getNextPhase(), ctx);
        });
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
                return Flux.just(interventionChunk(id, "等待人工处理中...",
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

        ToolCallContext callParam = ToolCallContext.builder()
                .callId("resume_" + req.getInterventionId())
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
                    return Flux.just(chunk)
                            .concatWith(Flux.defer(() -> {
                                appendSingleToolResult(ctx, result.withCallId(callParam.getCallId()), callParam.getCallId());
                                return dispatchAfterIteration(ctx)
                                        .thenMany(Mono.delay(Duration.ofMillis(ctx.getBackoffMs())).flux())
                                        .thenMany(Flux.<ChatStreamChunk>empty());
                            }));
                })
                .concatWith(Flux.defer(() -> runStream(ctx)));
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

        ToolCallContext callParam = ToolCallContext.builder()
                .callId("resume_" + req.getInterventionId())
                .toolName(req.getToolName())
                .arguments(modified)
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
                    return Flux.just(chunk)
                            .concatWith(Flux.defer(() -> {
                                appendSingleToolResult(ctx, result.withCallId(callParam.getCallId()), callParam.getCallId());
                                return dispatchAfterIteration(ctx)
                                        .thenMany(Mono.delay(Duration.ofMillis(ctx.getBackoffMs())).flux())
                                        .thenMany(Flux.<ChatStreamChunk>empty());
                            }));
                })
                .concatWith(Flux.defer(() -> runStream(ctx)));
    }

    private void appendSingleToolResult(LoopContext ctx, ToolResult result, String callId) {
        Msg lastMsg = ctx.getLastResponse() != null ? ctx.getLastResponse().getMessage() : null;
        if (lastMsg != null) ctx.addMessage(lastMsg);
        ctx.addMessage(Msg.builder(MsgRole.TOOL)
                .addToolResult(callId,
                        result.isSuccess() ? result.getContent()
                                : UI.TOOL_ERROR_PREFIX + result.getErrorMessage(),
                        !result.isSuccess())
                .build());
    }

    private Flux<ChatStreamChunk> executeAndContinue(Phase phase, LoopContext ctx) {
        if (phase.isReason()) {
            return executeReason(ctx);
        }
        if (phase.isAct()) {
            return executeAct(ctx, phase.getToolCalls());
        }
        if (phase.isObserve()) {
            return dispatchAfterIteration(ctx)
                    .thenMany(Flux.defer(() -> runStream(ctx)));
        }
        return Flux.empty();
    }

    // ---- Reason ----

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

                    List<ToolUseBlock> tools = extractToolCalls(resp);
                    if (tools.isEmpty()) {
                        Msg lastMsg = resp.getMessage();
                        if (lastMsg != null) ctx.addMessage(lastMsg);
                        return dispatchAfterIteration(ctx).thenMany(Flux.empty());
                    }
                    return executeAndContinue(Phase.act(tools), ctx);
                }));
    }

    // ---- Act ----

    private Flux<ChatStreamChunk> executeAct(LoopContext ctx, List<ToolUseBlock> toolCalls) {
        ctx.setIteration(ctx.getIteration() + 1);
        metrics.recordIteration(ctx.getAgentName(), ctx.getSessionId(),
                ctx.getIteration(), toolCalls.size());

        List<ToolResult> results = new java.util.concurrent.CopyOnWriteArrayList<>();

        return Flux.fromIterable(toolCalls)
                .flatMap(tc -> toolOrchestrator.execute(tc, ctx)
                        .doOnNext(results::add)
                        .map(result -> chunkFromToolResult(result)))
                .onErrorResume(e -> handleToolError(e, ctx, results))
                .concatWith(Flux.defer(() -> {
                    appendToolResults(ctx, results);
                    return dispatchAfterIteration(ctx)
                            .thenMany(Mono.delay(Duration.ofMillis(ctx.getBackoffMs())).flux())
                            .thenMany(Flux.<ChatStreamChunk>empty());
                }))
                .concatWith(Flux.defer(() -> runStream(ctx)));
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

    // ---- Intervention handling ----

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
        payload.put("type", Intervention.PAYLOAD_TYPE);
        payload.put("interventionId", id);
        payload.put("question", question);
        payload.put("interventionType", interventionType);
        payload.put("toolName", toolName);
        return ChatStreamChunk.builder()
                .delta(JsonUtils.toCompactJson(payload))
                .type(Intervention.CHUNK_TYPE)
                .finishReason(Intervention.FINISH_REASON)
                .build();
    }

    // ============================================================
    // 非流式 — 等待流式循环完成，返回最终模型响应
    // ============================================================

    public Mono<ChatResponse> run(LoopContext ctx) {
        return runStream(ctx)
                .then(Mono.fromSupplier(() -> {
                    ChatResponse resp = ctx.getLastResponse();
                    if (resp != null) return resp;
                    return new ChatResponse(null, new ChatUsage(0, 0), "empty", "");
                }));
    }

    // ============================================================
    // 中断处理
    // ============================================================

    private Flux<ChatStreamChunk> handleInterruptStream(LoopContext ctx) {
        Msg feedback = ctx.getFeedbackMsg();
        HookContext hc = buildHookContext(ctx);
        InterruptEvent ie = new InterruptEvent(
                feedback != null ? feedback.getTextContent() : UI.INTERRUPT_EXTERNAL, null);

        return hookDispatcher.dispatch(ie, hc)
                .flatMapMany(r -> {
                    if (r.isAbort()) {
                        return Flux.just(chunkFromText(
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
                    return Flux.just(chunkFromText(reason, FinishReason.INTERRUPTED));
                });
    }

    // ============================================================
    // 工具方法
    // ============================================================

    private void appendToolResults(LoopContext ctx, List<ToolResult> results) {
        Msg lastMsg = ctx.getLastResponse() != null
                ? ctx.getLastResponse().getMessage() : null;
        if (lastMsg == null) return;
        ctx.addMessage(lastMsg);

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

    private List<ToolUseBlock> extractToolCalls(ChatResponse response) {
        if (response == null || response.getMessage() == null) return List.of();
        List<ToolUseBlock> blocks = response.getMessage().getToolUseBlocks();
        return blocks != null ? blocks : List.of();
    }

    private HookContext buildHookContext(LoopContext ctx) {
        return new HookContext(ctx.getAgentName(), ctx.getRequestId(),
                ctx.getTenantId(), ctx.getSessionId(),
                ctx.getUserId(), ctx.getIteration(),
                List.of(), ctx.getAttributes());
    }

    private static ChatStreamChunk chunkFromResponse(ChatResponse resp) {
        if (resp == null || resp.getMessage() == null) return chunkFromText("", "");
        return ChatStreamChunk.builder()
                .delta(resp.getMessage() != null ? resp.getMessage().getTextContent() : "")
                .finishReason(resp.getFinishReason())
                .build();
    }

    private static ChatStreamChunk chunkFromText(String text, String finishReason) {
        return ChatStreamChunk.builder()
                .delta(text)
                .finishReason(finishReason)
                .build();
    }
}
