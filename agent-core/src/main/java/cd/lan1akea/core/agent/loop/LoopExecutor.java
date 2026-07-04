package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.CoreConstants.EventPayload;
import cd.lan1akea.core.CoreConstants.FinishReason;
import cd.lan1akea.core.CoreConstants.Logs;
import cd.lan1akea.core.CoreConstants.UI;
import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.message.*;
import cd.lan1akea.core.metrics.AgentMetrics;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.tool.*;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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

    public LoopExecutor(LoopDecisionEngine engine, ModelCallPipeline modelPipeline,
                         ToolCallOrchestrator toolOrchestrator, HookDispatcher hookDispatcher,
                         AgentMetrics metrics) {
        this.engine = engine;
        this.modelPipeline = modelPipeline;
        this.toolOrchestrator = toolOrchestrator;
        this.hookDispatcher = hookDispatcher;
        this.metrics = metrics;
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
                        .map(result -> {
                            String content = result.isSuccess()
                                    ? result.getContent()
                                    : UI.TOOL_ERROR_PREFIX + result.getErrorMessage();
                            return ChatStreamChunk.builder()
                                    .delta(content).type(ChatStreamChunk.TYPE_TEXT).build();
                        }))
                .concatWith(Flux.defer(() -> {
                    appendToolResults(ctx, results);
                    return dispatchAfterIteration(ctx)
                            .thenMany(Mono.delay(Duration.ofMillis(ctx.getBackoffMs())).flux())
                            .thenMany(Flux.<ChatStreamChunk>empty());
                }))
                .concatWith(Flux.defer(() -> runStream(ctx)));
    }

    // ============================================================
    // 非流式 — 从流式派生
    // ============================================================

    public Mono<ChatResponse> run(LoopContext ctx) {
        return runStream(ctx)
                .collectList()
                .map(ModelCallPipeline::assembleResponseFromChunks);
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
