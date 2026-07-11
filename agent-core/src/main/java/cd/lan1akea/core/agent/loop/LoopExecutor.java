package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.CoreConstants.EventPayload;
import cd.lan1akea.core.CoreConstants.FinishReason;
import cd.lan1akea.core.CoreConstants.HookSource;
import cd.lan1akea.core.CoreConstants.Intervention;
import cd.lan1akea.core.CoreConstants.Logs;
import cd.lan1akea.core.CoreConstants.Prompt;
import cd.lan1akea.core.CoreConstants.UI;
import cd.lan1akea.core.CoreConstants.Usage;
import cd.lan1akea.core.exception.HookAbortException;
import cd.lan1akea.core.exception.HumanInterventionException;
import cd.lan1akea.core.hook.*;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * ReAct 循环执行器。
 * 流式为 canonical 入口，通过直接线性递归驱动 Reason-Act-Observe 循环。
 *
 * <p>流程: runStream -> 介入恢复/中断检查/完成检查/最大迭代检查 -> executeReason
 *       -> extractToolCalls -> executeAct -> executeObserve -> runStream (递归)
 */
public class LoopExecutor {

    /** 日志记录器 */
    private static final Logger log = Logger.getLogger(LoopExecutor.class.getName());

    /** 模型调用管道，负责与 LLM 交互并获取回复 */
    private final ModelCallPipeline modelPipeline;

    /** 工具调用编排器，负责工具的执行与结果收集 */
    private final ToolCallOrchestrator toolOrchestrator;

    /** Hook 分发器，用于在循环各阶段触发回调 */
    private final HookDispatcher hookDispatcher;

    /** Agent 指标收集器，记录迭代次数等运行时数据 */
    private final AgentMetrics metrics;

    /** 人工介入恢复处理器 */
    private final InterventionResolver interventionResolver;

    /** Token 估算器，用于统计每次模型调用的实际 token 消耗 */
    private final TokenEstimator tokenEstimator;

    /**
     * 构造 ReAct 循环执行器。
     *
     * @param modelPipeline        模型调用管道
     * @param toolOrchestrator     工具调用编排器
     * @param hookDispatcher       Hook 分发器
     * @param metrics              Agent 指标收集器
     * @param tokenEstimator       Token 估算器
     * @param interventionResolver 介入恢复处理器
     */
    public LoopExecutor(ModelCallPipeline modelPipeline,
                         ToolCallOrchestrator toolOrchestrator, HookDispatcher hookDispatcher,
                         AgentMetrics metrics, TokenEstimator tokenEstimator,
                         InterventionResolver interventionResolver) {
        this.modelPipeline = modelPipeline;
        this.toolOrchestrator = toolOrchestrator;
        this.hookDispatcher = hookDispatcher;
        this.metrics = metrics;
        this.tokenEstimator = tokenEstimator;
        this.interventionResolver = interventionResolver;
    }

    /**
     * 启动流式 ReAct 循环（canonical 入口）。
     *
     * <p>线性流程：介入恢复 → 中断检查 → 完成检查 → 最大迭代检查 → 推理 → 行动/观察 → 递归。
     * 不再通过 Phase/Decision 状态机路由，直接在方法中顺序表达。
     *
     * @param ctx 循环上下文
     * @return 流式输出 chunk 序列
     */
    public Flux<ChatStreamChunk> runStream(LoopContext ctx) {
        return Flux.defer(() -> {
            if (ctx.getInterventionState().hasPending() && !ctx.isInterrupted()) {
                return resolveInterventionEntry(ctx);
            }
            if (ctx.isInterrupted()) {
                return handleInterruptStream(ctx);
            }
            if (ctx.isComplete()) {
                return finalizeStream(ctx);
            }
            if (ctx.getIteration() >= ctx.getMaxIterations()) {
                return summarizeThenReason(ctx);
            }
            return executeReason(ctx).concatWith(Flux.defer(() -> {
                List<ToolUseBlock> tools = extractToolCalls(ctx);
                if (tools != null && !tools.isEmpty()) {
                    return executeAct(ctx, tools).concatWith(executeObserve(ctx));
                }
                ctx.markComplete();
                return executeObserve(ctx);
            }));
        });
    }

    /**
     * 达到最大迭代时注入总结提示词并进入最后一轮推理。
     *
     * @param ctx 循环上下文
     * @return 总结轮次的流式 chunk 序列
     */
    private Flux<ChatStreamChunk> summarizeThenReason(LoopContext ctx) {
        HookContext hc = ctx.toHookContext();
        HookEvent event = new HookEvent(HookEventType.PRE_SUMMARIZE);
        event.setMessages(ctx.getMessages());

        return hookDispatcher.dispatch(event, hc)
                .flatMapMany(r -> {
                    if (r.isAbort()) {
                        return Flux.error(new HookAbortException(HookSource.HOOK, r.getAbortReason()));
                    }
                    if (event.getBypassMessage() != null) {
                        Msg bypass = event.getBypassMessage();
                        ctx.addMessage(bypass);
                        return Flux.just(ChatStreamChunk.of(
                                bypass.getTextContent(), FinishReason.STOP));
                    }
                    ctx.addMessage(SystemMessage.of(
                            Prompt.MAX_ITERATIONS_SUMMARY + Prompt.MAX_ITERATIONS_NO_TOOLS));
                    GenerateOptions opts = ctx.getGenerateOptions();
                    ctx.setGenerateOptions(GenerateOptions.builder()
                            .temperature(opts.getTemperature())
                            .maxTokens(opts.getMaxTokens())
                            .toolChoice(ToolChoicePolicy.NONE)
                            .build());
                    return executeReason(ctx);
                });
    }

    /** 找最后一条 assistant 消息的索引，-1 表示不存在 */
    private int lastAssistantIndex(LoopContext ctx) {
        List<Msg> msgs = ctx.getMessages();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i).getRole() == MsgRole.ASSISTANT) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 统一出口：插入 tool_result → emit chunk → 清空介入 → Observe → LLM。
     * 内置 onErrorResume 兜底执行失败，保证 tool_use 始终闭合。
     */
    private Flux<ChatStreamChunk> resolveAndContinue(LoopContext ctx, String callId,
                                                      Mono<ToolResult> execution) {
        int lastAssistant = lastAssistantIndex(ctx);
        int insertAt = lastAssistant >= 0 ? lastAssistant + 1 : ctx.getMessages().size();
        return execution
                .onErrorResume(e -> Mono.just(
                        ToolResult.failure(callId, "执行失败: " + e.getMessage())))
                .flatMapMany(result -> {
                    if (callId != null && insertAt > 0) {
                        ctx.getMessages().add(insertAt, Msg.builder(MsgRole.TOOL)
                                .addToolResult(callId,
                                        result.isSuccess() ? result.getContent()
                                                : result.getErrorMessage(),
                                        !result.isSuccess())
                                .build());
                    }
                    String displayText = result.isSuccess()
                            ? result.getContent()
                            : result.getErrorMessage();
                    ctx.getInterventionState().clear();
                    return Flux.just(ChatStreamChunk.builder()
                            .delta(displayText).type(ChatStreamChunk.TYPE_TEXT).build())
                            .concatWith(Flux.defer(() -> executeObserve(ctx)));
                });
    }


    /**
     * 执行推理阶段：调用模型获取回复。
     *
     * <p>流式收集模型分块 → 组装 ChatResponse → 设置 lastResponse 和 token。
     * 将 assistant 消息（含 tool_use blocks）追加到 ctx。
     * 工具调用检查和下一阶段路由由 runStream 的线性流程处理。
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
     * 应用 backoff。不递增 iteration、不分发 after-iteration hook ——
     * 这些由下游 executeObserve 统一处理。
     *
     * @param ctx       循环上下文
     * @param toolCalls 待执行的工具调用列表
     * @return 流式输出 chunk 序列
     */
    private Flux<ChatStreamChunk> executeAct(LoopContext ctx, List<ToolUseBlock> toolCalls) {
        // iteration 在 executeObserve 中递增，此处 +1 记录即将进入的迭代号
        metrics.recordIteration(ctx.getAgentName(), ctx.getSessionId(),
                ctx.getIteration() + 1, toolCalls.size());

        List<ToolResult> results = new CopyOnWriteArrayList<>();

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
     * 执行观察阶段并递归回 runStream。
     *
     * <p>递增迭代计数，分发 AFTER_ITERATION Hook，然后递归进入下一轮循环。
     *
     * @param ctx 循环上下文
     * @return 完成信号后递归
     */
    private Flux<ChatStreamChunk> executeObserve(LoopContext ctx) {
        ctx.setIteration(ctx.getIteration() + 1);
        return dispatchAfterIteration(ctx)
                .thenMany(Flux.defer(() -> runStream(ctx)));
    }

    /**
     * 处理工具执行过程中的异常。
     *
     * <p>根据异常类型分别处理：
     * <ul>
     *   <li>{@link HumanInterventionException} — 可恢复时进入介入流程，不可恢复时透传</li>
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
            return Flux.just(interventionResolver.createIntervention(hie, ctx));
        }
        // Other errors: add failure result and continue
        ToolResult failure = ToolResult.failure(UI.TOOL_EXEC_ERROR + e.getMessage());
        results.add(failure);
        return Flux.just(chunkFromToolResult(failure));
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
     * <p>通过 {@link HookDispatcher} 分发 {@link HookEvent#interrupt(String, String)}，
     * 如果 hook 返回 abort 则生成中断结束 chunk；
     * 否则注入反馈消息后恢复循环，或返回已中断原因。
     *
     * @param ctx 循环上下文
     * @return 中断处理后的流式 chunk 序列
     */
    private Flux<ChatStreamChunk> handleInterruptStream(LoopContext ctx) {
        Msg feedback = ctx.getFeedbackMsg();
        HookContext hc = ctx.toHookContext();
        HookEvent ie = HookEvent.interrupt(
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
                            && ctx.getLastResponse().getMessage() != null
                            ? ctx.getLastResponse().getMessage().getTextContent()
                            : UI.INTERRUPT_EXEC;
                    ChatResponse ir = buildInterruptedResponse(reason);
                    return Flux.just(ChatStreamChunk.of(
                            ir.getMessage().getTextContent(), FinishReason.INTERRUPTED));
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
        HookContext hc = ctx.toHookContext();
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
     * 介入恢复入口，委托给 InterventionResolver 后根据 Action 路由。
     *
     * @param ctx 循环上下文
     * @return 恢复后的流式 chunk 序列
     */
    private Flux<ChatStreamChunk> resolveInterventionEntry(LoopContext ctx) {
        InterventionResolver.ResolvedIntervention resolved =
                interventionResolver.resolveForRecovery(ctx);
        switch (resolved.getAction()) {
            case RE_ENTER:
                ctx.getInterventionState().clear();
                return runStream(ctx);
            case RETURN_CHUNK:
                return Flux.just(resolved.getChunk());
            case EXECUTE_AND_CONTINUE:
                return resolveAndContinue(ctx, resolved.getCallId(), resolved.getExecution());
            default:
                return Flux.empty();
        }
    }

    /**
     * 从 lastResponse 中提取 ToolUseBlock 列表。
     *
     * @param ctx 循环上下文
     * @return 工具调用列表，无则返回 null
     */
    private List<ToolUseBlock> extractToolCalls(LoopContext ctx) {
        ChatResponse resp = ctx.getLastResponse();
        if (resp != null && resp.getMessage() != null) {
            return resp.getMessage().getToolUseBlocks();
        }
        return null;
    }

    /**
     * 完成流：构建 stop chunk 并返回。
     *
     * @param ctx 循环上下文
     * @return stop chunk Flux
     */
    private Flux<ChatStreamChunk> finalizeStream(LoopContext ctx) {
        ChatResponse lastResp = ctx.getLastResponse();
        if (lastResp != null && lastResp.getMessage() != null) {
            return Flux.just(ChatStreamChunk.builder()
                    .delta(lastResp.getMessage().getTextContent())
                    .finishReason(lastResp.getFinishReason() != null
                            ? lastResp.getFinishReason() : FinishReason.STOP)
                    .build());
        }
        return Flux.just(ChatStreamChunk.of("", FinishReason.STOP));
    }

    /**
     * 构建中断终止响应。
     *
     * @param reason 中断原因
     * @return 中断响应
     */
    private static ChatResponse buildInterruptedResponse(String reason) {
        Msg msg = Msg.builder(MsgRole.ASSISTANT)
                .addText(UI.INTERRUPT_PREFIX + reason + UI.INTERRUPT_SUFFIX)
                .putMetadata(EventPayload.INTERRUPT_ID, reason)
                .build();
        return new ChatResponse(msg, new ChatUsage(0, 0), FinishReason.INTERRUPTED, "");
    }
}
