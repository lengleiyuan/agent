package cd.lan1akea.core.hook;

import cd.lan1akea.core.CoreConstants.EventPayload;
import cd.lan1akea.core.CoreConstants.FinishReason;
import cd.lan1akea.core.CoreConstants.HookSource;
import cd.lan1akea.core.CoreConstants.UI;
import cd.lan1akea.core.agent.loop.LoopContext;
import cd.lan1akea.core.context.RuntimeContext;
import cd.lan1akea.core.exception.HookAbortException;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.MsgRole;
import cd.lan1akea.core.CoreConstants.Prompt;
import cd.lan1akea.core.message.SystemMessage;
import cd.lan1akea.core.model.ChatResponse;
import cd.lan1akea.core.model.ChatStreamChunk;
import cd.lan1akea.core.model.GenerateOptions;
import cd.lan1akea.core.model.ToolChoicePolicy;
import cd.lan1akea.core.tool.ToolCallContext;
import cd.lan1akea.core.tool.ToolResult;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.function.Function;

/**
 * Hook 管线门面。
 *
 * <p>职责：编排 {@link HookDispatcher} + {@link AroundHookChain}，对外暴露两类入口：
 * <ul>
 *   <li><b>简单分发</b> — {@link #dispatch(HookEvent, HookContext)}：透传，结果由调用方自行处理</li>
 *   <li><b>完整管线</b> — {@link #aroundReasoning} / {@link #aroundToolCall} / {@link #aroundCall}：
 *       封装 pre-hook → aroundHook(core) → post-hook</li>
 * </ul>
 *
 * <p>业务逻辑（abort/skip 后的具体行为、模型调用、工具执行）通过 {@link Function} 注入，
 * HookPipeline 只做编排，不感知业务细节。
 */
public class HookPipeline {

    /** Hook 分发器 */
    private final HookDispatcher dispatcher;
    /** AroundHook 洋葱链 */
    private final AroundHookChain aroundChain;

    /**
     * 构建 Hook 管线门面。
     *
     * @param dispatcher  Hook 分发器
     * @param aroundChain AroundHook 链
     */
    public HookPipeline(HookDispatcher dispatcher, AroundHookChain aroundChain) {
        this.dispatcher = dispatcher;
        this.aroundChain = aroundChain;
    }

    // ============================================================
    // 简单分发
    // ============================================================

    /**
     * 直接分发 Hook 事件。
     *
     * <p>用于无 aroundHook 包裹的简单事件（AFTER_ITERATION / ON_INTERRUPT / PRE_SUMMARIZE 等）。
     * 调用方自行处理 {@link HookResult}（abort / interrupt / skip / bypass 分支）。
     *
     * @param event 事件数据
     * @param ctx   Hook 上下文
     * @return Hook 处理结果
     */
    public Mono<HookResult> dispatch(HookEvent event, HookContext ctx) {
        return dispatcher.dispatch(event, ctx);
    }

    // ============================================================
    // 推理管线
    // ============================================================

    /**
     * 执行完整推理管线。
     *
     * <p>流程：
     * <ol>
     *   <li>PRE_REASONING — abort→error, interrupt→chunk, bypass→chunk</li>
     *   <li>aroundHook.aroundReasoningStream(modelCall)</li>
     *   <li>POST_REASONING（fire-and-forget）</li>
     * </ol>
     *
     * @param ctx       循环上下文
     * @param modelCall 模型调用逻辑（入参为 HookEvent 供 AroundHook 修改，出参为流式分块）
     * @return 模型流式输出
     */
    public Flux<ChatStreamChunk> aroundReasoning(LoopContext ctx,
                                                  Function<HookEvent, Flux<ChatStreamChunk>> modelCall) {
        HookContext hc = ctx.toHookContext();
        HookEvent preReasoning = new HookEvent(HookEventType.PRE_REASONING);
        preReasoning.setMessages(ctx.getMessages());

        return dispatch(preReasoning, hc)
                .flatMapMany(r -> {
                    if (r.isAbort()) {
                        return Flux.error(new HookAbortException(
                                HookSource.HOOK, r.getAbortReason()));
                    }
                    if (r.isInterrupt()) {
                        return buildInterruptChunk(r.getInterruptReason());
                    }
                    if (preReasoning.getBypassMessage() != null) {
                        String text = preReasoning.getBypassMessage().getTextContent();
                        return Flux.just(ChatStreamChunk.of(
                                text != null ? text : "", FinishReason.STOP));
                    }
                    return aroundChain.aroundReasoningStream(preReasoning, hc, modelCall)
                            .concatWith(fireAndForget(HookEventType.POST_REASONING, hc));
                });
    }

    // ============================================================
    // 工具管线
    // ============================================================

    /**
     * 执行完整工具调用管线。
     *
     * <p>流程：PRE_TOOL_CALL → aroundHook(execution) → POST_TOOL_CALL。
     * pre-hook 的 abort / skip 由管线统一处理（abort→failure, skip→success），
     * 工具执行逻辑通过 {@code execution} 注入。
     *
     * @param preEvent  PRE_TOOL_CALL 事件（需已设置 callParam、tool）
     * @param ctx       Hook 上下文
     * @param execution 工具执行逻辑，入参 ToolCallContext，出参 ToolResult
     * @return 工具执行结果
     */
    public Mono<ToolResult> aroundToolCall(HookEvent preEvent, HookContext ctx,
                                            Function<ToolCallContext, Mono<ToolResult>> execution) {
        return dispatch(preEvent, ctx)
                .flatMap(r -> {
                    if (r.isAbort()) {
                        return Mono.just(ToolResult.failure(
                                UI.TOOL_BLOCKED + r.getAbortReason()));
                    }
                    if (r.isSkip()) {
                        ToolResult skipped = ToolResult.success(
                                UI.TOOL_SKIPPED_PREFIX
                                        + (r.getSkipReason() != null
                                                ? r.getSkipReason() : UI.TOOL_SKIPPED_DEFAULT));
                        return firePostToolHook(preEvent.getCallParam(), skipped, ctx)
                                .thenReturn(skipped);
                    }
                    return executeWithAround(preEvent, ctx, execution);
                });
    }

    /**
     * 直接执行工具调用，跳过 PRE Hook（用于介入恢复场景）。
     *
     * @param event     PRE_TOOL_CALL 事件（需已设置 callParam）
     * @param ctx       Hook 上下文
     * @param execution 工具执行逻辑
     * @return 工具执行结果
     */
    public Mono<ToolResult> aroundToolCallDirect(HookEvent event, HookContext ctx,
                                                   Function<ToolCallContext, Mono<ToolResult>> execution) {
        return executeWithAround(event, ctx, execution);
    }

    /** aroundHook 包裹 → 桥接事件 → fire POST_TOOL */
    private Mono<ToolResult> executeWithAround(HookEvent event, HookContext ctx,
                                                Function<ToolCallContext, Mono<ToolResult>> execution) {
        ToolCallContext callParam = event.getCallParam();
        return aroundChain.aroundToolCall(event, ctx,
                        e -> execution.apply(callParam)
                                .map(result -> {
                                    e.setPayload(EventPayload.TOOL_RESULT, result);
                                    return e;
                                }))
                .flatMap(e -> Mono.justOrEmpty(
                        (ToolResult) e.getPayload(EventPayload.TOOL_RESULT)))
                .flatMap(result -> firePostToolHook(callParam, result, ctx));
    }

    /** 分发 POST_TOOL_CALL Hook（fire-and-forget） */
    private Mono<ToolResult> firePostToolHook(ToolCallContext param, ToolResult result,
                                               HookContext ctx) {
        HookEvent post = new HookEvent(HookEventType.POST_TOOL_CALL);
        post.setCallParam(param);
        post.setResult(result);
        return dispatch(post, ctx).thenReturn(result);
    }

    // ============================================================
    // 请求级包裹
    // ============================================================

    /**
     * 包裹整个请求（直接委托 AroundHookChain）。
     *
     * @param ctx  运行时上下文
     * @param core 核心流
     * @return 包裹后的流式分块
     */
    public Flux<ChatStreamChunk> aroundCall(RuntimeContext ctx,
                                             Function<HookEvent, Flux<ChatStreamChunk>> core) {
        if (aroundChain.isEmpty()) {
            return core.apply(new HookEvent(null));
        }
        return aroundChain.aroundCallStream(
                new HookEvent(null), HookContext.from(ctx, 0), core);
    }

    // ============================================================
    // 模型响应后管线
    // ============================================================

    /**
     * 模型响应后处理管线。
     *
     * <p>dispatch(POST_MODEL) → abort → error → 从 event 读取 usage chunk → 返回。
     * 默认行为由系统内置 TokenEstimationHook 提供（写入 ctx + token 估算 + 构建 usage chunk）。
     *
     * @param ctx      循环上下文
     * @param response 组装后的 ChatResponse
     * @return usage chunk 的 Mono
     */
    public Mono<ChatStreamChunk> onPostModel(LoopContext ctx, ChatResponse response) {
        HookEvent event = new HookEvent(HookEventType.POST_MODEL);
        event.setPayload(EventPayload.LOOP_CONTEXT, ctx);
        event.setPayload(EventPayload.RESPONSE, response);
        HookContext hc = ctx.toHookContext();
        return dispatch(event, hc)
                .flatMap(r -> {
                    if (r.isAbort())
                        return Mono.error(new HookAbortException(
                                HookSource.HOOK, r.getAbortReason()));
                    return Mono.justOrEmpty(
                            (ChatStreamChunk) event.getPayload(EventPayload.USAGE_CHUNK));
                });
    }

    // ============================================================
    // 总结管线
    // ============================================================

    /**
     * 总结前管线。
     *
     * <p>dispatch(PRE_SUMMARIZE) → abort → error
     * → bypass → 注入 bypass 消息 + 返回 bypass chunk
     * → 默认 → 注入总结提示词 + 禁用工具，返回 continueLoop
     *
     * @param ctx 循环上下文
     * @return PreSummarizeResult
     */
    public Mono<PreSummarizeResult> preSummarize(LoopContext ctx) {
        HookEvent event = new HookEvent(HookEventType.PRE_SUMMARIZE);
        event.setMessages(ctx.getMessages());
        HookContext hc = ctx.toHookContext();
        return dispatch(event, hc)
                .flatMap(r -> {
                    if (r.isAbort())
                        return Mono.error(new HookAbortException(
                                HookSource.HOOK, r.getAbortReason()));
                    if (event.getBypassMessage() != null) {
                        Msg bypass = event.getBypassMessage();
                        ctx.addMessage(bypass);
                        return Mono.just(PreSummarizeResult.chunk(
                                ChatStreamChunk.of(
                                        bypass.getTextContent(), FinishReason.STOP)));
                    }
                    ctx.addMessage(SystemMessage.of(
                            Prompt.MAX_ITERATIONS_SUMMARY + Prompt.MAX_ITERATIONS_NO_TOOLS));
                    GenerateOptions opts = ctx.getGenerateOptions();
                    ctx.setGenerateOptions(GenerateOptions.builder()
                            .temperature(opts.getTemperature())
                            .maxTokens(opts.getMaxTokens())
                            .toolChoice(ToolChoicePolicy.NONE)
                            .build());
                    return Mono.just(PreSummarizeResult.continueLoop());
                });
    }

    // ============================================================
    // 中断管线
    // ============================================================

    /**
     * 中断处理管线。
     *
     * <p>dispatch(ON_INTERRUPT) → abort → 返回中断终止 chunk
     * → 有 feedback → 注入 feedback + 清除中断 → 返回 recover
     * → 默认 → 返回中断终止 chunk
     *
     * @param ctx 循环上下文
     * @return InterruptResult
     */
    public Mono<InterruptResult> onInterrupt(LoopContext ctx) {
        Msg feedback = ctx.getFeedbackMsg();
        HookEvent event = HookEvent.interrupt(
                feedback != null ? feedback.getTextContent() : UI.INTERRUPT_EXTERNAL, null);
        HookContext hc = ctx.toHookContext();
        return dispatch(event, hc)
                .flatMap(r -> {
                    if (r.isAbort())
                        return Mono.just(InterruptResult.chunk(
                                ChatStreamChunk.of(
                                        UI.INTERRUPT_STREAM_PREFIX + r.getAbortReason()
                                                + UI.INTERRUPT_SUFFIX,
                                        FinishReason.INTERRUPTED)));
                    if (feedback != null) {
                        ctx.addMessage(feedback);
                        ctx.clearInterrupt();
                        return Mono.just(InterruptResult.recover());
                    }
                    String reason = firstNonEmpty(
                            ctx.getInterventionState().getPausedReason(),
                            UI.INTERRUPT_EXEC);
                    return Mono.just(InterruptResult.chunk(
                            ChatStreamChunk.of(
                                    buildInterruptText(reason), FinishReason.INTERRUPTED)));
                });
    }

    // ============================================================
    // private helpers
    // ============================================================

    /** 返回第一个非空字符串 */
    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) return v;
        }
        return "";
    }

    /** fire-and-forget 模式分发 Hook */
    private <T> Flux<T> fireAndForget(HookEventType type, HookContext ctx) {
        return dispatch(new HookEvent(type), ctx).then(Mono.<T>empty()).flux();
    }

    /** 构建中断终止 chunk */
    private Flux<ChatStreamChunk> buildInterruptChunk(String reason) {
        Msg irMsg = Msg.builder(MsgRole.ASSISTANT)
                .addText(UI.INTERRUPT_PREFIX + reason + UI.INTERRUPT_SUFFIX)
                .putMetadata(EventPayload.INTERRUPT_ID, reason)
                .build();
        return Flux.just(ChatStreamChunk.of(
                irMsg.getTextContent(), FinishReason.INTERRUPTED));
    }

    private String buildInterruptText(String reason) {
        return UI.INTERRUPT_PREFIX + reason + UI.INTERRUPT_SUFFIX;
    }

    // ============================================================
    // 结果类型
    // ============================================================

    /**
     * PRE_SUMMARIZE Hook 处理结果。
     */
    public static class PreSummarizeResult {

        /**
         * 结果动作类型。
         */
        public enum Action {
            /**
             * 继续循环（默认：已注入总结提示词 + 禁用工具）
             */
            CONTINUE,
            /**
             * 返回 chunk 给调用方下发（bypass / abort 后）
             */
            RETURN_CHUNK
        }

        private final Action action;
        private final ChatStreamChunk chunk;

        private PreSummarizeResult(Action action, ChatStreamChunk chunk) {
            this.action = action;
            this.chunk = chunk;
        }

        /**
         * 返回 chunk 给调用方（bypass 分支）。
         */
        public static PreSummarizeResult chunk(ChatStreamChunk c) {
            return new PreSummarizeResult(Action.RETURN_CHUNK, c);
        }

        /**
         * 继续循环（默认分支）。
         */
        public static PreSummarizeResult continueLoop() {
            return new PreSummarizeResult(Action.CONTINUE, null);
        }

        /**
         * @return 结果动作类型
         */
        public Action getAction() { return action; }

        /**
         * @return chunk（RETURN_CHUNK 时有效）
         */
        public ChatStreamChunk getChunk() { return chunk; }
    }

    /**
     * ON_INTERRUPT Hook 处理结果。
     */
    public static class InterruptResult {

        /**
         * 结果动作类型。
         */
        public enum Action {
            /**
             * 恢复循环（有 feedback 消息已注入）
             */
            RECOVER,
            /**
             * 返回中断终止 chunk
             */
            RETURN_CHUNK
        }

        private final Action action;
        private final ChatStreamChunk chunk;

        private InterruptResult(Action action, ChatStreamChunk chunk) {
            this.action = action;
            this.chunk = chunk;
        }

        /**
         * 恢复循环。
         */
        public static InterruptResult recover() {
            return new InterruptResult(Action.RECOVER, null);
        }

        /**
         * 返回中断终止 chunk。
         */
        public static InterruptResult chunk(ChatStreamChunk c) {
            return new InterruptResult(Action.RETURN_CHUNK, c);
        }

        /**
         * @return 结果动作类型
         */
        public Action getAction() { return action; }

        /**
         * @return chunk（RETURN_CHUNK 时有效）
         */
        public ChatStreamChunk getChunk() { return chunk; }
    }
}
