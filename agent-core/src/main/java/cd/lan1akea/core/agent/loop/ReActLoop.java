package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.CoreConstants.EventPayload;
import cd.lan1akea.core.CoreConstants.FinishReason;
import cd.lan1akea.core.CoreConstants.HookSource;
import cd.lan1akea.core.CoreConstants.Logs;
import cd.lan1akea.core.CoreConstants.UI;
import cd.lan1akea.core.CoreConstants.Prompt;
import cd.lan1akea.core.exception.HookAbortException;
import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.message.*;
import cd.lan1akea.core.metrics.AgentMetrics;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.approval.ApprovalStore;
import cd.lan1akea.core.tool.*;
import cd.lan1akea.core.util.JsonUtils;

import java.util.logging.Logger;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ReAct 循环引擎。
 * 构建时注入只读服务，每次请求通过 LoopContext（纯数据）驱动。
 * 每次迭代后保存检查点，支持崩溃恢复。
 * 上下文压缩和记忆检索通过 PreReasoningHook 实现，不硬编码在此。
 */
public class ReActLoop {

    private static final Logger log = Logger.getLogger(ReActLoop.class.getName());

    /**
     * 用于 LLM 推理的聊天模型。
     */
    private final ChatModel model;
    /**
     * 用于执行工具调用的工具执行器。
     */
    private final ToolExecutor toolExecutor;
    /**
     * 用于扩展点的 Hook 分发器。
     */
    private final HookDispatcher hookDispatcher;
    /**
     * 可用工具架构注册表。
     */
    private final ToolRegistry toolRegistry;
    /**
     * 封装推理和工具调用执行的 AroundHook 链。
     */
    private final AroundHookChain aroundHookChain;
    /**
     * 指标收集器（可选，默认 NOOP）。
     */
    private AgentMetrics metrics = AgentMetrics.NOOP;

    /**
     * 使用默认 AroundHookChain 创建 ReActLoop。
     *
     * @param model          用于 LLM 推理的聊天模型
     * @param toolExecutor   用于执行工具调用的执行器
     * @param hookDispatcher 用于 Hook 扩展点的分发器
     * @param toolRegistry   可用工具架构注册表
     */
    public ReActLoop(ChatModel model, ToolExecutor toolExecutor,
                     HookDispatcher hookDispatcher, ToolRegistry toolRegistry) {
        this(model, toolExecutor, hookDispatcher, toolRegistry, new AroundHookChain());
    }

    public ReActLoop(ChatModel model, ToolExecutor toolExecutor,
                     HookDispatcher hookDispatcher, ToolRegistry toolRegistry,
                     AroundHookChain aroundHookChain) {
        this.model = model;
        this.toolExecutor = toolExecutor;
        this.hookDispatcher = hookDispatcher;
        this.toolRegistry = toolRegistry;
        this.aroundHookChain = aroundHookChain != null ? aroundHookChain : new AroundHookChain();
    }

    public void setMetrics(AgentMetrics v) { this.metrics = v != null ? v : AgentMetrics.NOOP; }

    /**
     * 执行 ReAct 循环至完成（非流式）。
     * 处理中断、最大迭代摘要、推理、行动和观察。
     *
     * @param ctx 携带消息和状态的循环上下文
     * @return 最终聊天响应
     */
    public Mono<ChatResponse> execute(LoopContext ctx) {
        return Mono.defer(() -> {
            if (ctx.isInterrupted()) {
                return handleExternalInterrupt(ctx);
            }
            if (ctx.getIteration() >= ctx.getMaxIterations()) {
                return summarize(ctx);
            }
            return reasoning(ctx)
                    .onErrorResume(e -> handleError(ctx, e).then(Mono.error(e)))
                    .flatMap(response -> {
                        ctx.setLastResponse(response);
                        if (response.getUsage() != null) ctx.addTokens(response.getUsage().getTotalTokens());
                        List<ToolUseBlock> toolCalls = extractToolCalls(response);
                        if (toolCalls.isEmpty()) {
                            return Mono.just(response);
                        }
                        return acting(ctx, toolCalls)
                                .flatMap(results -> {
                                    appendToolResults(ctx, results);
                                    ctx.setIteration(ctx.getIteration() + 1);
                                    metrics.recordIteration(ctx.getAgentName(), ctx.getSessionId(),
                                            ctx.getIteration(), toolCalls.size());
                                    return dispatchAfterIteration(ctx).thenReturn(ctx);
                                })
                                .delayElement(Duration.ofMillis(ctx.getBackoffMs()))
                                .flatMap(this::execute);
                    });
        });
    }


    /**
     * 执行 ReAct 循环（流式），返回流式分块。
     * 处理中断、最大迭代摘要、推理、行动和观察。
     *
     * @param ctx 携带消息和状态的循环上下文
     * @return 聊天流式分块的 Flux
     */
    public Flux<ChatStreamChunk> executeStream(LoopContext ctx) {
        return Flux.defer(() -> {
            if (ctx.isInterrupted()) {
                if (ctx.getFeedbackMsg() != null) {
                    return handleInterruptStream(ctx);
                }
                return Flux.empty();
            }
            if (ctx.getIteration() >= ctx.getMaxIterations())
                return summarizeStream(ctx);

            List<ChatStreamChunk> chunkBuffer = new ArrayList<>();

            return reasoningStream(ctx)
                    .doOnNext(chunkBuffer::add)
                    .concatWith(Flux.defer(() -> {
                        ChatResponse response = buildResponseFromChunks(chunkBuffer);
                        if (response == null) return Flux.empty();

                        ctx.setLastResponse(response);
                        if (response.getUsage() != null) ctx.addTokens(response.getUsage().getTotalTokens());

                        List<ToolUseBlock> toolCalls = extractToolCalls(response);
                        if (toolCalls.isEmpty()) {
                            Msg lastMsg = response.getMessage();
                            if (lastMsg != null) ctx.addMessage(lastMsg);
                            return dispatchAfterIteration(ctx).thenMany(Flux.empty());
                        }

                        ctx.setIteration(ctx.getIteration() + 1);
                        List<ToolResult> toolResults = new CopyOnWriteArrayList<>();
                        return actingStream(ctx, toolCalls, toolResults)
                                .doOnComplete(() -> appendToolResults(ctx, toolResults))
                                .concatWith(dispatchAfterIteration(ctx).thenMany(Flux.defer(() -> executeStream(ctx))));
                    }));
        });
    }


    /**
     * 执行推理阶段（非流式）：分发 Hook，调用模型并携带工具，通过 around-hook 链处理响应。
     *
     * @param ctx 循环上下文
     * @return 模型的聊天响应
     */
    Mono<ChatResponse> reasoning(LoopContext ctx) {
        HookContext hc = buildHookContext(ctx);

        ReasoningEvent pre = new ReasoningEvent(HookEventType.PRE_REASONING);

        return hookDispatcher.dispatch(pre, hc)
                .flatMap(r -> {
                    if (r.isAbort()) return Mono.error(
                            new HookAbortException(HookSource.HOOK, r.getAbortReason()));
                    if (r.isInterrupt()) return Mono.just(buildInterrupted(r.getInterruptReason()));
                    // KB 命中：绕过模型直接返回
                    if (pre.getBypassMessage() != null)
                        return Mono.just(new ChatResponse(pre.getBypassMessage(), new ChatUsage(0, 0), FinishReason.STOP, ""));

                    List<ToolSchema> schemas = toolRegistry.getSchemas(
                            ctx.getTenantId(), ctx.getUserId(), ctx.getSessionId());

                    return aroundHookChain.aroundReasoning(pre, hc,
                                    (HookEvent e) -> {
                                        HookEvent modelEvent = new HookEvent(HookEventType.PRE_MODEL_CALL);
                                        final long llmStart = System.currentTimeMillis();
                                        return hookDispatcher.dispatch(modelEvent, hc)
                                                .flatMap(mr -> mr.isAbort()
                                                        ? Mono.error(new HookAbortException(HookSource.MODEL, mr.getAbortReason()))
                                                        : model.chatWithTools(ctx.getMessages(), schemas, ctx.getGenerateOptions()))
                                                .map(resp -> {
                                                    long llmLatency = System.currentTimeMillis() - llmStart;
                                                    int pt = resp.getUsage() != null ? resp.getUsage().getPromptTokens() : 0;
                                                    int ct = resp.getUsage() != null ? resp.getUsage().getCompletionTokens() : 0;
                                                    metrics.recordLlmCall(model.getModelName(), model.getProvider(),
                                                            llmLatency, pt, ct, true, null);
                                                    e.setPayload(EventPayload.CHAT_RESPONSE, resp);
                                                    return e;
                                                })
                                                .flatMap(ev ->
                                                        hookDispatcher.dispatch(
                                                                        new HookEvent(HookEventType.POST_MODEL_CALL), hc)
                                                                .thenReturn(ev));
                                    })
                            .flatMap(e -> {
                                ChatResponse resp = e.getPayload(EventPayload.CHAT_RESPONSE);
                                ReasoningEvent post = new ReasoningEvent(HookEventType.POST_REASONING);
                                return hookDispatcher.dispatch(post, hc)
                                        .thenReturn(resp);
                            });
                });
    }


    /**
     * 执行推理阶段（流式）：分发 Hook 并流式输出模型结果。
     *
     * @param ctx 循环上下文
     * @return 来自模型的流式分块 Flux
     */
    Flux<ChatStreamChunk> reasoningStream(LoopContext ctx) {
        HookContext hc = buildHookContext(ctx);

        ReasoningEvent pre = new ReasoningEvent(HookEventType.PRE_REASONING);

        return hookDispatcher.dispatch(pre, hc)
                .flatMapMany(r -> {
                    if (r.isAbort())
                        return Flux.error(new HookAbortException(HookSource.HOOK, r.getAbortReason()));
                    if (r.isInterrupt()) {
                        ChatResponse ir = buildInterrupted(r.getInterruptReason());
                        return Flux.just(ChatStreamChunk.builder()
                                .delta(ir.getMessage().getTextContent())
                                .finishReason(FinishReason.INTERRUPTED).build());
                    }
                    // KB 命中：绕过模型直接返回，不触发 POST hooks
                    if (pre.getBypassMessage() != null) {
                        String text = pre.getBypassMessage().getTextContent();
                        return Flux.just(ChatStreamChunk.builder()
                                .delta(text != null ? text : "").finishReason(FinishReason.STOP).build());
                    }
                    List<ToolSchema> schemas = toolRegistry.getSchemas(ctx.getTenantId(), ctx.getUserId(), ctx.getSessionId());
                    return hookDispatcher.dispatch(
                                    new HookEvent(HookEventType.PRE_MODEL_CALL), hc)
                            .flatMapMany(mr -> mr.isAbort()
                                    ? Flux.error(new HookAbortException(HookSource.MODEL, mr.getAbortReason()))
                                    : aroundHookChain.aroundReasoningStream(pre, hc,
                                    e -> model.streamWithTools(ctx.getMessages(), schemas, ctx.getGenerateOptions())))
                            .concatWith(Mono.defer(() ->
                                    hookDispatcher.dispatch(
                                                    new HookEvent(HookEventType.POST_MODEL_CALL), hc)
                                            .then(Mono.<ChatStreamChunk>empty())))
                            .concatWith(Mono.defer(() ->
                                    hookDispatcher.dispatch(
                                                    new ReasoningEvent(HookEventType.POST_REASONING), hc)
                                            .then(Mono.<ChatStreamChunk>empty())));
                });
    }


    /**
     * 执行行动阶段（非流式）：执行所有工具调用并收集结果。
     *
     * @param ctx       循环上下文
     * @param toolCalls 要执行的工具调用列表
     * @return 工具执行结果列表
     */
    Mono<List<ToolResult>> acting(LoopContext ctx, List<ToolUseBlock> toolCalls) {
        HookContext hc = buildHookContext(ctx);
        return Flux.fromIterable(toolCalls)
                .flatMap(tc -> executeSingleTool(ctx, tc, hc))
                .collectList();
    }


    /**
     * 执行行动阶段（流式）：执行所有工具调用并以分块形式发射结果。
     *
     * @param ctx       循环上下文
     * @param toolCalls 要执行的工具调用列表
     * @return 每次工具执行的结果分块 Flux
     */
    Flux<ChatStreamChunk> actingStream(LoopContext ctx, List<ToolUseBlock> toolCalls,
                                       List<ToolResult> resultsCollector) {
        HookContext hc = buildHookContext(ctx);
        return Flux.fromIterable(toolCalls)
                .flatMap(tc -> executeSingleTool(ctx, tc, hc)
                        .map(result -> {
                            resultsCollector.add(result);
                            String content = result.isSuccess() ? result.getContent() : UI.TOOL_ERROR_PREFIX + result.getErrorMessage();
                            return ChatStreamChunk.builder()
                                    .delta(content).type(ChatStreamChunk.TYPE_TEXT).build();
                        }));
    }

    /**
     * 通过 Hook 链和工具执行器执行单个工具调用。
     * 如果工具需要用户批准，处理挂起情况。
     *
     * @param ctx 循环上下文
     * @param tc  要执行的工具使用块
     * @param hc  Hook 上下文
     * @return 工具执行结果
     */
    Mono<ToolResult> executeSingleTool(LoopContext ctx, ToolUseBlock tc, HookContext hc) {
        ToolCallContext param = ToolCallContext.builder()
                .callId(tc.getId()).toolName(tc.getName())
                .arguments(tc.getArgumentsMap())
                .tenantId(ctx.getTenantId()).userId(ctx.getUserId())
                .sessionId(ctx.getSessionId()).attributes(ctx.getAttributes())
                .build();

        // 同一个 event 贯穿 PRE → AroundHook → POST
        ToolCallEvent event = new ToolCallEvent(HookEventType.PRE_TOOL_CALL, param);
        // 注入 Tool 实例到 event，Hook 无需自行注入 ToolRegistry
        event.setTool(toolRegistry.getForContext(
                ctx.getTenantId(), ctx.getUserId(), ctx.getSessionId(), tc.getName()));

        return hookDispatcher.dispatch(event, hc)
                .flatMap(r -> {
                    if (r.isAbort()) {
                        return Mono.just(ToolResult.failure(UI.TOOL_BLOCKED + r.getAbortReason()));
                    }
                    if (r.isSkip()) {
                        ToolResult skipped = ToolResult.success(
                                UI.TOOL_SKIPPED_PREFIX + (r.getSkipReason() != null ? r.getSkipReason() : UI.TOOL_SKIPPED_DEFAULT));
                        ToolCallEvent postSkip = new ToolCallEvent(HookEventType.POST_TOOL_CALL, param, skipped);
                        return hookDispatcher.dispatch(postSkip, hc)
                                .thenReturn(skipped);
                    }

                    return aroundHookChain.aroundToolCall(event, hc,
                                    (HookEvent e) -> toolExecutor.execute(param)
                                            .map(result -> {
                                                e.setPayload("tool_result", result);
                                                ((ToolCallEvent) e).setResult(result);
                                                return e;
                                            }))
                            .flatMap(e -> {
                                ToolResult result = e.getPayload("tool_result");
                                if (result == null && e instanceof ToolCallEvent tce) result = tce.getResult();
                                ToolCallEvent post = new ToolCallEvent(HookEventType.POST_TOOL_CALL, param,
                                        result != null ? result : ToolResult.failure(UI.TOOL_NO_RESULT));
                                return hookDispatcher.dispatch(post, hc)
                                        .thenReturn(result != null ? result : ToolResult.failure("无结果"));
                            });
                })
                .onErrorResume(ToolSuspendException.class, e -> {
                    // ApprovalStore 预检：若 bypassKey 已批准 → 标记 approved 并重试
                    ApprovalStore approvalStore = toolExecutor.getApprovalStore();
                    if (!param.isApproved() && approvalStore != null && event.getTool() != null) {
                        String sessionId = ctx.getSessionId();
                        if (sessionId != null && approvalStore.isApproved(sessionId, e.getBypassKey())) {
                            param.setApproved(true);
                            return toolExecutor.execute(param);
                        }
                    }
                    // 未批准 → 中断流程
                    InterruptEvent ie = new InterruptEvent(e.getQuestion(), tc.getName());
                    ie.setPayload(EventPayload.ARGUMENTS, tc.getArgumentsMap());
                    ie.setPayload(EventPayload.RECENT_MESSAGES, ctx.getMessages());
                    if (event.getTool() != null) {
                        ie.setPayload(EventPayload.TOOL_DESCRIPTION, event.getTool().getDescription());
                        ie.setPayload(EventPayload.RISK_LEVEL, event.getTool().getRiskLevel());
                    }
                    return hookDispatcher.dispatch(ie, hc)
                            .flatMap(ir -> {
                                if (ir.isAbort()) {
                                    return Mono.just(ToolResult.failure(UI.APPROVAL_DENIED));
                                }
                                ctx.interrupt();
                                return Mono.just(ToolResult.failure(UI.APPROVAL_WAITING + e.getQuestion()));
                            });
                })
                .map(r -> r.withCallId(param.getCallId()));
    }

    /**
     * 执行观察阶段（非流式）：将工具结果追加到消息中，增加迭代计数，持久化对话轮次并保存检查点。
     *
     * @param ctx     循环上下文
     * @param results 要观察的工具执行结果
     * @return 更新后的循环上下文
     */
    /**
     * 将工具结果消息追加到循环上下文，将结果与其调用 ID 配对。
     *
     * @param ctx     循环上下文
     * @param results 要追加的工具结果
     */
    private void appendToolResults(LoopContext ctx, List<ToolResult> results) {
        Msg lastMsg = ctx.getLastResponse() != null ? ctx.getLastResponse().getMessage() : null;
        if (lastMsg == null) return;
        ctx.addMessage(lastMsg);
        for (ToolResult r : results) {
            String callId = r.getCallId();
            if (callId == null) continue;
            ctx.addMessage(Msg.builder(MsgRole.TOOL)
                    .addToolResult(callId,
                            r.isSuccess() ? r.getContent() : UI.TOOL_ERROR_PREFIX + r.getErrorMessage(),
                            !r.isSuccess()).build());
        }
    }

    /**
     * 将当前对话轮次（用户、助手和工具消息）持久化到状态存储。
     *
     * @param ctx 循环上下文
     */
    /**
     * 分发 AFTER_ITERATION 事件到 Hook 链。
     * Hook 失败不中断主流程，降级为日志告警。
     *
     * @param ctx 循环上下文
     * @return 分发完成的 Mono
     */
    Mono<Void> dispatchAfterIteration(LoopContext ctx) {
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
     * 达到最大迭代次数时生成摘要（非流式）。
     *
     * @param ctx 循环上下文
     * @return 模型的摘要聊天响应
     */
    private Mono<ChatResponse> summarize(LoopContext ctx) {
        List<Msg> messages = ctx.getMessages();
        if (messages.isEmpty()) return Mono.justOrEmpty(ctx.getLastResponse());

        Msg summaryPrompt = SystemMessage.of(
                Prompt.MAX_ITERATIONS_SUMMARY + Prompt.MAX_ITERATIONS_NO_TOOLS);
        messages.add(summaryPrompt);

        List<ToolSchema> schemas = toolRegistry.getSchemas(ctx.getTenantId(), ctx.getUserId(), ctx.getSessionId());
        Integer genMaxTokens = ctx.getGenerateOptions().getMaxTokens();
        int maxOut = genMaxTokens != null ? Math.min(genMaxTokens, 1024) : 1024;
        GenerateOptions noTools = GenerateOptions.builder()
                .temperature(ctx.getGenerateOptions().getTemperature())
                .maxTokens(maxOut)
                .toolChoice(ToolChoicePolicy.NONE)
                .build();

        return model.chatWithTools(messages, schemas, noTools);
    }

    /**
     * 达到最大迭代次数时生成摘要（流式）。
     *
     * @param ctx 循环上下文
     * @return 模型的摘要流式分块 Flux
     */
    private Flux<ChatStreamChunk> summarizeStream(LoopContext ctx) {
        List<Msg> messages = ctx.getMessages();
        if (messages.isEmpty()) return Flux.empty();

        Msg summaryPrompt = SystemMessage.of(
                Prompt.MAX_ITERATIONS_SUMMARY + Prompt.MAX_ITERATIONS_NO_TOOLS);
        messages.add(summaryPrompt);

        List<ToolSchema> schemas = toolRegistry.getSchemas(ctx.getTenantId(), ctx.getUserId(), ctx.getSessionId());
        Integer genMaxTokens = ctx.getGenerateOptions().getMaxTokens();
        int maxOut = genMaxTokens != null ? Math.min(genMaxTokens, 1024) : 1024;
        GenerateOptions noTools = GenerateOptions.builder()
                .temperature(ctx.getGenerateOptions().getTemperature())
                .maxTokens(maxOut)
                .toolChoice(ToolChoicePolicy.NONE)
                .build();

        return model.streamWithTools(messages, schemas, noTools);
    }

    /**
     * 处理外部中断（非流式）：分发中断事件，根据反馈恢复循环或返回最后响应。
     *
     * @param ctx 循环上下文
     * @return 处理中断后的最终聊天响应
     */
    private Mono<ChatResponse> handleExternalInterrupt(LoopContext ctx) {
        Msg feedback = ctx.getFeedbackMsg();
        HookContext hc = buildHookContext(ctx);
        InterruptEvent ie = new InterruptEvent(feedback != null ? feedback.getTextContent() : UI.INTERRUPT_EXTERNAL, null);

        return hookDispatcher.dispatch(ie, hc)
                .flatMap(r -> {
                    if (r.isAbort()) {
                        return Mono.fromCallable(() ->
                                ctx.getLastResponse() != null ? ctx.getLastResponse()
                                        : buildInterrupted(r.getAbortReason()));
                    }
                    if (feedback != null) {
                        // 有反馈消息 → 注入消息并继续 ReAct 循环
                        ctx.addMessage(feedback);
                        ctx.clearInterrupt();
                        return execute(ctx);
                    }
                    // 无反馈 → 真正中断，返回最后响应
                    return Mono.fromCallable(() ->
                            ctx.getLastResponse() != null ? ctx.getLastResponse()
                                    : buildInterrupted(UI.INTERRUPT_EXEC));
                });
    }

    /**
     * 处理外部中断（流式）：分发中断事件，根据反馈恢复流式或发射中断分块。
     *
     * @param ctx 循环上下文
     * @return 处理中断后的流式分块 Flux
     */
    private Flux<ChatStreamChunk> handleInterruptStream(LoopContext ctx) {
        Msg feedback = ctx.getFeedbackMsg();
        HookContext hc = buildHookContext(ctx);
        InterruptEvent ie = new InterruptEvent(feedback != null ? feedback.getTextContent() : UI.INTERRUPT_EXTERNAL, null);

        return hookDispatcher.dispatch(ie, hc)
                .flatMapMany(r -> {
                    if (r.isAbort()) {
                        return Flux.just(ChatStreamChunk.builder()
                                .delta(UI.INTERRUPT_STREAM_PREFIX + r.getAbortReason() + UI.INTERRUPT_SUFFIX)
                                .finishReason(FinishReason.INTERRUPTED).build());
                    }
                    if (feedback != null) {
                        ctx.addMessage(feedback);
                        ctx.clearInterrupt();
                        return executeStream(ctx);
                    }
                    String reason = ctx.getLastResponse() != null
                            ? ctx.getLastResponse().getMessage().getTextContent()
                            : UI.INTERRUPT_EXEC;
                    return Flux.just(ChatStreamChunk.builder()
                            .delta(reason).finishReason("interrupted").build());
                });
    }


    /**
     * 通过在 Hook 中分发错误事件来处理循环执行中的错误。
     *
     * @param ctx   循环上下文
     * @param error 发生的错误
     * @return 空 Mono 或如果 Hook 中止则返回错误
     */
    Mono<Void> handleError(LoopContext ctx, Throwable error) {
        return hookDispatcher.dispatch(
                        new ErrorEvent(error), buildHookContext(ctx))
                .flatMap(r -> r.isAbort()
                        ? Mono.error(new HookAbortException(HookSource.ERROR_HOOK, r.getAbortReason()))
                        : Mono.empty());
    }


    /**
     * 从循环上下文构建 Hook 上下文用于 Hook 分发调用。
     *
     * @param ctx 循环上下文
     * @return 新的 Hook 上下文
     */
    HookContext buildHookContext(LoopContext ctx) {
        return new HookContext(ctx.getAgentName(), ctx.getRequestId(),
                ctx.getTenantId(), ctx.getSessionId(),
                ctx.getUserId(), ctx.getIteration(), List.of(), ctx.getAttributes());
    }

    /**
     * 构建表示执行已中断的聊天响应。
     *
     * @param reason 中断原因
     * @return 中断聊天响应
     */
    ChatResponse buildInterrupted(String reason) {
        Msg msg = Msg.builder(MsgRole.ASSISTANT)
                .addText(UI.INTERRUPT_PREFIX + reason + UI.INTERRUPT_SUFFIX)
                .putMetadata(MessageMetadataKeys.INTERRUPT_ID, reason).build();
        return new ChatResponse(msg, new ChatUsage(0, 0), FinishReason.INTERRUPTED, "");
    }

    /**
     * 通过事件总线发射指定类型的 agent 事件。
     *
     * @param ctx  循环上下文
     * @param type 要发射的事件类型
     */

    /**
     * 从聊天响应中提取工具使用块，如果没有则返回空列表。
     *
     * @param response 要提取的聊天响应
     * @return 工具使用块列表，不会为 null
     */
    List<ToolUseBlock> extractToolCalls(ChatResponse response) {
        if (response == null || response.getMessage() == null) return List.of();
        List<ToolUseBlock> blocks = response.getMessage().getToolUseBlocks();
        return blocks != null ? blocks : List.of();
    }

    /**
     * 从流式分块列表重构单个 ChatResponse。
     * 将文本增量和工具使用 start/delta 分块聚合为内容块。
     *
     * @param chunks 要组装的流式分块
     * @return 重构后的聊天响应，如果分块为空则返回 null
     */
    ChatResponse buildResponseFromChunks(List<ChatStreamChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return null;

        StringBuilder text = new StringBuilder();
        Map<String, String> toolArgs = new LinkedHashMap<>();
        Map<String, String> toolNames = new LinkedHashMap<>();

        for (ChatStreamChunk chunk : chunks) {
            if (chunk.getDelta() != null && ChatStreamChunk.TYPE_TEXT.equals(chunk.getType())) {
                text.append(chunk.getDelta());
            }

            if (ChatStreamChunk.TYPE_TOOL_USE_START.equals(chunk.getType()) && chunk.getToolUseId() != null) {
                toolNames.put(chunk.getToolUseId(), chunk.getToolName() != null ? chunk.getToolName() : "");
                toolArgs.put(chunk.getToolUseId(), "");
            }

            if (ChatStreamChunk.TYPE_TOOL_USE_DELTA.equals(chunk.getType())
                    && chunk.getToolUseId() != null && chunk.getDelta() != null) {
                toolArgs.merge(chunk.getToolUseId(), chunk.getDelta(), String::concat);
            }
        }

        String finishReason = FinishReason.COMPLETED;
        for (int i = chunks.size() - 1; i >= 0; i--) {
            if (chunks.get(i).getFinishReason() != null) {
                finishReason = chunks.get(i).getFinishReason();
                break;
            }
        }

        List<ContentBlock> blocks = new ArrayList<>();
        if (!text.isEmpty()) blocks.add(new TextBlock(text.toString()));
        for (Map.Entry<String, String> e : toolArgs.entrySet()) {
            String id = e.getKey();
            blocks.add(new ToolUseBlock(id, toolNames.getOrDefault(id, ""),
                    JsonUtils.repairJson(e.getValue())));
        }

        Msg msg = new AssistantMessage(blocks, null);
        return new ChatResponse(msg, new ChatUsage(0, 0), finishReason, null);
    }

}
