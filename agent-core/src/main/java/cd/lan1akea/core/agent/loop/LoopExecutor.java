package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.CoreConstants.EventPayload;
import cd.lan1akea.core.CoreConstants.FinishReason;
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

    private List<Msg> truncateMessages(List<Msg> messages) {
        int size = messages.size();
        int from = Math.max(0, size - 20);
        return new ArrayList<>(messages.subList(from, size));
    }

    private ChatStreamChunk chunkFromToolResult(ToolResult result) {
        String content = result.isSuccess()
                ? result.getContent()
                : UI.TOOL_ERROR_PREFIX + result.getErrorMessage();
        return ChatStreamChunk.builder()
                .delta(content).type(ChatStreamChunk.TYPE_TEXT).build();
    }

    private ChatStreamChunk interventionChunk(String id, String question,
                                               String interventionType, String toolName) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "intervention_required");
        payload.put("interventionId", id);
        payload.put("question", question);
        payload.put("interventionType", interventionType);
        payload.put("toolName", toolName);
        return ChatStreamChunk.builder()
                .delta(JsonUtils.toCompactJson(payload))
                .type("intervention")
                .finishReason("interrupted")
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
