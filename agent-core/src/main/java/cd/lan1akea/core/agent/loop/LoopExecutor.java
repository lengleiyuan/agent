package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.CoreConstants.EventPayload;
import cd.lan1akea.core.CoreConstants.Intervention;
import cd.lan1akea.core.CoreConstants.Logs;
import cd.lan1akea.core.CoreConstants.UI;
import cd.lan1akea.core.exception.HumanInterventionException;
import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.message.*;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.tool.*;
import cd.lan1akea.core.util.ChatResponseUtil;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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

    /** Hook 管线门面，统一管理所有 Hook 分发 */
    private final HookPipeline hookPipeline;

    /** 人工介入恢复处理器 */
    private final InterventionResolver interventionResolver;

    /**
     * 构造 ReAct 循环执行器。
     *
     * @param modelPipeline        模型调用管道
     * @param toolOrchestrator     工具调用编排器
     * @param hookPipeline         Hook 管线门面
     * @param interventionResolver 介入恢复处理器
     */
    public LoopExecutor(ModelCallPipeline modelPipeline,
                         ToolCallOrchestrator toolOrchestrator, HookPipeline hookPipeline,
                         InterventionResolver interventionResolver) {
        this.modelPipeline = modelPipeline;
        this.toolOrchestrator = toolOrchestrator;
        this.hookPipeline = hookPipeline;
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
                return interventionResolver.recover(ctx, () -> executeObserve(ctx))
                        .switchIfEmpty(Flux.defer(() -> runStream(ctx)));
            }
            if (ctx.isInterrupted()) {
                return handleInterruptStream(ctx);
            }
            if (ctx.isComplete()) {
                return Flux.empty();
            }
            if (ctx.getIteration() >= ctx.getMaxIterations()) {
                return summarizeThenReason(ctx);
            }
            return reasonThenActOrObserve(ctx);
        });
    }



    private Flux<ChatStreamChunk> reasonThenActOrObserve(LoopContext ctx) {
        return executeReason(ctx).concatWith(Flux.defer(() -> {
            List<ToolUseBlock> tools = extractToolCalls(ctx);
            if (tools != null && !tools.isEmpty()) {
                return executeAct(ctx, tools).concatWith(executeObserve(ctx));
            }
            ctx.markComplete();
            return executeObserve(ctx);
        }));
    }

    /**
     * 达到最大迭代时注入总结提示词并进入最后一轮推理。
     *
     * <p>委托 HookPipeline.preSummarize 处理 PRE_SUMMARIZE 分发和注入逻辑。
     *
     * @param ctx 循环上下文
     * @return 总结轮次的流式 chunk 序列
     */
    private Flux<ChatStreamChunk> summarizeThenReason(LoopContext ctx) {
        return hookPipeline.preSummarize(ctx)
                .flatMapMany(r -> {
                    if (r.getAction() == HookPipeline.PreSummarizeResult.Action.RETURN_CHUNK)
                        return Flux.just(r.getChunk());
                    return reasonThenActOrObserve(ctx);
                });
    }

    /**
     * 执行推理阶段：调用模型获取回复。
     *
     * <p>流式收集模型分块 → ChatResponseUtil.fromChunks 组装 → HookPipeline.onPostModel 后处理。
     * 后处理（写入 ctx + token 估算 + usage chunk）由 TokenEstimationHook 默认执行。
     *
     * @param ctx 循环上下文
     * @return 模型推理的流式分块
     */
    private Flux<ChatStreamChunk> executeReason(LoopContext ctx) {
        List<ChatStreamChunk> buffer = new ArrayList<>();
        return modelPipeline.executeStream(ctx)
                .doOnNext(buffer::add)
                .concatWith(Flux.defer(() -> {
                    ChatResponse resp = ChatResponseUtil.fromChunks(buffer);
                    return resp != null
                            ? hookPipeline.onPostModel(ctx, resp)
                            : Flux.empty();
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
     * <p>委托 HookPipeline.onInterrupt 处理 ON_INTERRUPT 分发和分支逻辑。
     *
     * @param ctx 循环上下文
     * @return 中断处理后的流式 chunk 序列
     */
    private Flux<ChatStreamChunk> handleInterruptStream(LoopContext ctx) {
        return hookPipeline.onInterrupt(ctx)
                .flatMapMany(r -> {
                    if (r.getAction() == HookPipeline.InterruptResult.Action.RECOVER)
                        return runStream(ctx);
                    return Flux.just(r.getChunk());
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
        HookEvent event = new HookEvent(HookEventType.AFTER_ITERATION);
        event.setPayload(EventPayload.LOOP_CONTEXT, ctx);
        HookContext hc = ctx.toHookContext();
        return hookPipeline.dispatch(event, hc)
                .onErrorResume(e -> {
                    log.warning(Logs.AFTER_ITERATION_FAILED
                            + ctx.getRequestId() + Logs.ERR_DETAIL + e.getMessage());
                    return Mono.empty();
                })
                .then();
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

}
