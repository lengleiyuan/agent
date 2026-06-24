package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.agent.AgentEvent;
import cd.lan1akea.core.agent.AgentEventType;
import cd.lan1akea.core.event.EventBus;
import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.message.*;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.session.SessionId;
import cd.lan1akea.core.state.AgentState;
import cd.lan1akea.core.state.AgentStateStore;
import cd.lan1akea.core.session.ChatTurn;
import cd.lan1akea.core.tool.*;
import cd.lan1akea.core.util.IdGenerator;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * ReAct 循环引擎。
 * <p>
 * 构建时注入只读服务，每次请求通过 LoopContext（纯数据）驱动。
 * 每次迭代后保存检查点，支持崩溃恢复。
 * 上下文压缩和记忆检索通过 PreReasoningHook 实现，不硬编码在此。
 * </p>
 */
public class ReActLoop {

    private final ChatModel model;
    private final ToolExecutor toolExecutor;
    private final HookDispatcher hookDispatcher;
    private final ToolRegistry toolRegistry;
    private final AgentStateStore stateStore;
    private final EventBus eventBus;

    public ReActLoop(ChatModel model, ToolExecutor toolExecutor,
                      HookDispatcher hookDispatcher, ToolRegistry toolRegistry,
                      AgentStateStore stateStore, EventBus eventBus) {
        this.model = model;
        this.toolExecutor = toolExecutor;
        this.hookDispatcher = hookDispatcher;
        this.toolRegistry = toolRegistry;
        this.stateStore = stateStore;
        this.eventBus = eventBus;
    }

    // ========================================================================
    // 主循环（非流式）
    // ========================================================================

    public Mono<ChatResponse> execute(LoopContext ctx) {
        return Mono.defer(() -> {
            if (ctx.isInterrupted()) {
                return handleExternalInterrupt(ctx);
            }
            if (ctx.getIteration() >= ctx.getMaxIterations()) {
                return summarizeStep(ctx);
            }
            return reasoningStep(ctx)
                .onErrorResume(e -> handleError(ctx, e).then(Mono.error(e)))
                .flatMap(response -> {
                    ctx.setLastResponse(response);
                    if (response.getUsage() != null) ctx.addTokens(response.getUsage().getTotalTokens());
                    List<ToolUseBlock> toolCalls = extractToolCalls(response);
                    if (toolCalls.isEmpty()) {
                        emitEvent(ctx, AgentEventType.COMPLETED);
                        return Mono.just(response);
                    }
                    return actingStep(ctx, toolCalls)
                        .flatMap(results -> observationStep(ctx, results))
                        .flatMap(this::execute);
                });
        });
    }

    // ========================================================================
    // 主循环（流式）—— 完整 ReAct + 工具调用
    // ========================================================================

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

            return reasoningStream(ctx)
                .collectList()
                .flatMapMany(chunks -> {
                    ChatResponse response = buildResponseFromChunks(chunks);
                    if (response == null) return Flux.fromIterable(chunks);

                    ctx.setLastResponse(response);
                    if (response.getUsage() != null) ctx.addTokens(response.getUsage().getTotalTokens());

                    List<ToolUseBlock> toolCalls = extractToolCalls(response);
                    if (toolCalls.isEmpty()) {
                        emitEvent(ctx, AgentEventType.COMPLETED);
                        return Flux.fromIterable(chunks);
                    }

                    ctx.setIteration(ctx.getIteration() + 1);
                    return Flux.fromIterable(chunks)
                        .concatWith(actingStream(ctx, toolCalls))
                        .concatWith(observationStream(ctx, toolCalls))
                        .concatWith(executeStream(ctx));
                });
        });
    }

    // ========================================================================
    // 推理阶段（非流式）
    // ========================================================================

    Mono<ChatResponse> reasoningStep(LoopContext ctx) {
        HookContext hc = buildHookContext(ctx);
        emitEvent(ctx, AgentEventType.REASONING_START);

        ReasoningEvent pre = new ReasoningEvent(HookEventType.PRE_REASONING);

        return hookDispatcher.dispatch(HookEventType.PRE_REASONING, pre, hc)
            .flatMap(r -> {
                if (r.isAbort()) return Mono.error(
                    new cd.lan1akea.core.exception.HookAbortException("hook", r.getAbortReason()));
                if (r.isInterrupt()) return Mono.just(buildInterrupted(r.getInterruptReason()));
                List<ToolSchema> schemas = toolRegistry.getSchemas(ctx.getTenantId(), ctx.getUserId(), ctx.getSessionId());
                return model.chatWithTools(ctx.getMessages(), schemas, ctx.getGenerateOptions());
            })
            .flatMap(resp -> {
                emitEvent(ctx, AgentEventType.REASONING_END);
                ReasoningEvent post = new ReasoningEvent(HookEventType.POST_REASONING);
                return hookDispatcher.dispatch(HookEventType.POST_REASONING, post, hc)
                    .thenReturn(resp);
            });
    }

    // ========================================================================
    // 推理阶段（流式）
    // ========================================================================

    Flux<ChatStreamChunk> reasoningStream(LoopContext ctx) {
        HookContext hc = buildHookContext(ctx);
        emitEvent(ctx, AgentEventType.REASONING_START);

        ReasoningEvent pre = new ReasoningEvent(HookEventType.PRE_REASONING);

        return hookDispatcher.dispatch(HookEventType.PRE_REASONING, pre, hc)
            .flatMapMany(r -> {
                if (r.isAbort())
                    return Flux.error(new cd.lan1akea.core.exception.HookAbortException("hook", r.getAbortReason()));
                if (r.isInterrupt()) {
                    ChatResponse ir = buildInterrupted(r.getInterruptReason());
                    return Flux.just(ChatStreamChunk.builder()
                        .delta(ir.getMessage().getTextContent())
                        .finishReason("interrupted").build());
                }
                List<ToolSchema> schemas = toolRegistry.getSchemas(ctx.getTenantId(), ctx.getUserId(), ctx.getSessionId());
                return model.streamWithTools(ctx.getMessages(), schemas, ctx.getGenerateOptions());
            })
            .doOnNext(chunk ->
                hookDispatcher.dispatch(HookEventType.ON_STREAM_CHUNK,
                    new HookEvent(HookEventType.ON_STREAM_CHUNK), hc).subscribe())
            .doOnComplete(() -> {
                emitEvent(ctx, AgentEventType.REASONING_END);
                ReasoningEvent post = new ReasoningEvent(HookEventType.POST_REASONING);
                hookDispatcher.dispatch(HookEventType.POST_REASONING, post, hc).subscribe();
            });
    }

    // ========================================================================
    // 行动阶段（非流式）
    // ========================================================================

    Mono<List<ToolResult>> actingStep(LoopContext ctx, List<ToolUseBlock> toolCalls) {
        HookContext hc = buildHookContext(ctx);
        emitEvent(ctx, AgentEventType.ACTING_START);
        ActingEvent ae = new ActingEvent(HookEventType.PRE_ACTING);
        ae.setToolCalls(toolCalls);

        return hookDispatcher.dispatch(HookEventType.PRE_ACTING, ae, hc)
            .flatMapMany(r -> Flux.fromIterable(toolCalls))
            .flatMap(tc -> executeSingleTool(ctx, tc, hc))
            .collectList()
            .flatMap(results -> {
                emitEvent(ctx, AgentEventType.ACTING_END);
                ActingEvent post = new ActingEvent(HookEventType.POST_ACTING);
                return hookDispatcher.dispatch(HookEventType.POST_ACTING, post, hc)
                    .thenReturn(results);
            });
    }

    // ========================================================================
    // 行动阶段（流式）
    // ========================================================================

    Flux<ChatStreamChunk> actingStream(LoopContext ctx, List<ToolUseBlock> toolCalls) {
        HookContext hc = buildHookContext(ctx);
        emitEvent(ctx, AgentEventType.ACTING_START);
        ActingEvent ae = new ActingEvent(HookEventType.PRE_ACTING);
        ae.setToolCalls(toolCalls);

        return hookDispatcher.dispatch(HookEventType.PRE_ACTING, ae, hc)
            .flatMapMany(r -> Flux.fromIterable(toolCalls))
            .concatMap(tc -> executeSingleTool(ctx, tc, hc))
            .concatMap(result -> {
                String content = result.isSuccess() ? result.getContent() : "[错误] " + result.getErrorMessage();
                return Flux.just(ChatStreamChunk.builder()
                    .delta(content).type(ChatStreamChunk.TYPE_TEXT).build());
            })
            .doOnComplete(() -> {
                emitEvent(ctx, AgentEventType.ACTING_END);
                ActingEvent post = new ActingEvent(HookEventType.POST_ACTING);
                hookDispatcher.dispatch(HookEventType.POST_ACTING, post, hc).subscribe();
            });
    }

    Mono<ToolResult> executeSingleTool(LoopContext ctx, ToolUseBlock tc, HookContext hc) {
        ToolCallParam param = new ToolCallParam(tc.getId(), tc.getName(), tc.getArguments(),
            ctx.getTenantId(), ctx.getUserId(), ctx.getSessionId(), ctx.getAttributes());

        // 携带工具调用参数的事件
        ToolCallEvent preEvent = new ToolCallEvent(HookEventType.PRE_TOOL_CALL, param);

        return hookDispatcher.dispatch(HookEventType.PRE_TOOL_CALL, preEvent, hc)
            .flatMap(r -> r.isAbort()
                ? Mono.just(ToolResult.failure("工具调用被阻止: " + r.getAbortReason()))
                : toolExecutor.execute(param, ctx.getTenantId()))
            .onErrorResume(ToolSuspendException.class, e ->
                hookDispatcher.dispatch(HookEventType.ON_INTERRUPT,
                    new InterruptEvent(e.getQuestion(), tc.getName()), hc)
                    .flatMap(ir -> Mono.just(ir.isAbort()
                        ? ToolResult.failure("操作被拒绝")
                        : ToolResult.failure("等待审批: " + e.getQuestion()))))
            .flatMap(result -> {
                // 携带工具结果的事件
                ToolCallEvent postEvent = new ToolCallEvent(HookEventType.POST_TOOL_CALL, param, result);
                return hookDispatcher.dispatch(HookEventType.POST_TOOL_CALL, postEvent, hc)
                    .thenReturn(result);
            });
    }

    // ========================================================================
    // 观察（非流式）
    // ========================================================================

    Mono<LoopContext> observationStep(LoopContext ctx, List<ToolResult> results) {
        appendToolResults(ctx, results);
        ctx.setIteration(ctx.getIteration() + 1);
        persistTurn(ctx);
        saveCheckpoint(ctx);
        return Mono.just(ctx);
    }

    // ========================================================================
    // 观察（流式）
    // ========================================================================

    Flux<ChatStreamChunk> observationStream(LoopContext ctx, List<ToolUseBlock> toolCalls) {
        persistTurn(ctx);
        saveCheckpoint(ctx);
        return Flux.empty();
    }

    private void appendToolResults(LoopContext ctx, List<ToolResult> results) {
        Msg lastMsg = ctx.getLastResponse() != null ? ctx.getLastResponse().getMessage() : null;
        List<ToolUseBlock> toolCalls = lastMsg != null ? lastMsg.getToolUseBlocks() : List.of();
        for (int i = 0; i < results.size(); i++) {
            ToolResult r = results.get(i);
            String callId = i < toolCalls.size() ? toolCalls.get(i).getId() : "tool_" + i;
            ctx.addMessage(Msg.builder(MsgRole.TOOL)
                .addToolResult(callId,
                    r.isSuccess() ? r.getContent() : "[错误] " + r.getErrorMessage(),
                    !r.isSuccess()).build());
        }
    }

    // ========================================================================
    // 持久化
    // ========================================================================

    private void persistTurn(LoopContext ctx) {
        if (stateStore == null || ctx.getSessionId() == null) return;
        Msg userMsg = null, assistantMsg = null;
        List<Msg> userMsgs = new ArrayList<>();
        List<Msg> assistantMsgs = new ArrayList<>();
        List<Msg> toolMsgs = new ArrayList<>();

        for (Msg m : ctx.getMessages()) {
            if (m.getRole() == MsgRole.USER) { userMsg = m; userMsgs.add(m); }
            if (m.getRole() == MsgRole.ASSISTANT) { assistantMsg = m; assistantMsgs.add(m); }
            if (m.getRole() == MsgRole.TOOL) toolMsgs.add(m);
        }

        ChatTurn turn = new ChatTurn(IdGenerator.nextId(),
            Long.parseLong(ctx.getSessionId()), ctx.getIteration(),
            userMsg != null ? userMsg.getTextContent() : "",
            assistantMsg != null ? assistantMsg.getTextContent() : null,
            null, LocalDateTime.now(),
            userMsgs, assistantMsgs, toolMsgs);

        stateStore.addTurn(new SessionId(ctx.getSessionId()), turn).subscribe();
    }

    private void saveCheckpoint(LoopContext ctx) {
        if (stateStore == null || ctx.getSessionId() == null) return;

        AgentState state = new AgentState(ctx.getAgentName(), ctx.getSessionId(),
            ctx.getIteration(), new ArrayList<>(ctx.getMessages()),
            Map.of(), ctx.getTotalTokens(), false, null,
            System.currentTimeMillis());

        stateStore.saveCheckpoint(state).subscribe();
    }

    // ========================================================================
    // 超迭代摘要
    // ========================================================================

    private Mono<ChatResponse> summarizeStep(LoopContext ctx) {
        emitEvent(ctx, AgentEventType.SUMMARIZED);
        List<Msg> messages = ctx.getMessages();
        if (messages.isEmpty()) return Mono.justOrEmpty(ctx.getLastResponse());

        Msg summaryPrompt = SystemMessage.of(
            "你已达到最大迭代次数。请用一段话总结你已经完成的工作和当前状态。" +
            "不要调用任何工具，只做文字总结。");
        messages.add(summaryPrompt);

        List<ToolSchema> schemas = toolRegistry.getSchemas(ctx.getTenantId(), ctx.getUserId(), ctx.getSessionId());
        GenerateOptions noTools = GenerateOptions.builder()
            .temperature(ctx.getGenerateOptions().getTemperature())
            .maxTokens(Math.min(ctx.getGenerateOptions().getMaxTokens(), 1024))
            .build();

        HookContext hc = buildHookContext(ctx);
        return hookDispatcher.dispatch(HookEventType.ON_SUMMARY,
                new HookEvent(HookEventType.ON_SUMMARY), hc)
            .then(model.chatWithTools(messages, schemas, noTools));
    }

    private Flux<ChatStreamChunk> summarizeStream(LoopContext ctx) {
        emitEvent(ctx, AgentEventType.SUMMARIZED);
        List<Msg> messages = ctx.getMessages();
        if (messages.isEmpty()) return Flux.empty();

        Msg summaryPrompt = SystemMessage.of(
            "你已达到最大迭代次数。请用一段话总结你已经完成的工作和当前状态。" +
            "不要调用任何工具，只做文字总结。");
        messages.add(summaryPrompt);

        List<ToolSchema> schemas = toolRegistry.getSchemas(ctx.getTenantId(), ctx.getUserId(), ctx.getSessionId());
        GenerateOptions noTools = GenerateOptions.builder()
            .temperature(ctx.getGenerateOptions().getTemperature())
            .maxTokens(Math.min(ctx.getGenerateOptions().getMaxTokens(), 1024))
            .build();

        HookContext hc = buildHookContext(ctx);
        return hookDispatcher.dispatch(HookEventType.ON_SUMMARY,
                new HookEvent(HookEventType.ON_SUMMARY), hc)
            .thenMany(model.streamWithTools(messages, schemas, noTools));
    }

    // ========================================================================
    // 外部中断处理
    // ========================================================================

    private Mono<ChatResponse> handleExternalInterrupt(LoopContext ctx) {
        emitEvent(ctx, AgentEventType.INTERRUPTED);
        Msg feedback = ctx.getFeedbackMsg();
        HookContext hc = buildHookContext(ctx);
        InterruptEvent ie = new InterruptEvent(feedback != null ? feedback.getTextContent() : "外部中断", null);

        return hookDispatcher.dispatch(HookEventType.ON_INTERRUPT, ie, hc)
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
                        : buildInterrupted("执行已被中断"));
            });
    }

    private Flux<ChatStreamChunk> handleInterruptStream(LoopContext ctx) {
        emitEvent(ctx, AgentEventType.INTERRUPTED);
        Msg feedback = ctx.getFeedbackMsg();
        HookContext hc = buildHookContext(ctx);
        InterruptEvent ie = new InterruptEvent(feedback != null ? feedback.getTextContent() : "外部中断", null);

        return hookDispatcher.dispatch(HookEventType.ON_INTERRUPT, ie, hc)
            .flatMapMany(r -> {
                if (r.isAbort()) {
                    return Flux.just(ChatStreamChunk.builder()
                        .delta("[中断: " + r.getAbortReason() + "]")
                        .finishReason("interrupted").build());
                }
                if (feedback != null) {
                    ctx.addMessage(feedback);
                    ctx.clearInterrupt();
                    return executeStream(ctx);
                }
                String reason = ctx.getLastResponse() != null
                    ? ctx.getLastResponse().getMessage().getTextContent()
                    : "执行已被中断";
                return Flux.just(ChatStreamChunk.builder()
                    .delta(reason).finishReason("interrupted").build());
            });
    }

    // ========================================================================
    // 错误处理
    // ========================================================================

    Mono<Void> handleError(LoopContext ctx, Throwable error) {
        emitEvent(ctx, AgentEventType.ERROR);
        return hookDispatcher.dispatch(HookEventType.ON_ERROR,
                new ErrorEvent(error), buildHookContext(ctx))
            .flatMap(r -> r.isAbort()
                ? Mono.error(new cd.lan1akea.core.exception.HookAbortException("ErrorHook", r.getAbortReason()))
                : Mono.empty());
    }

    // ========================================================================
    // 辅助
    // ========================================================================

    HookContext buildHookContext(LoopContext ctx) {
        return new HookContext(ctx.getAgentName(), ctx.getTenantId(), ctx.getSessionId(),
            ctx.getUserId(), ctx.getIteration(), new ArrayList<>(), ctx.getAttributes());
    }

    ChatResponse buildInterrupted(String reason) {
        Msg msg = Msg.builder(MsgRole.ASSISTANT)
            .addText("[执行已中断: " + reason + "]")
            .putMetadata(MessageMetadataKeys.INTERRUPT_ID, reason).build();
        return new ChatResponse(msg, new ChatUsage(0, 0), "interrupted", "");
    }

    void emitEvent(LoopContext ctx, AgentEventType type) {
        if (eventBus != null) eventBus.publish(new AgentEvent(type, ctx.getAgentName())).subscribe();
    }

    List<ToolUseBlock> extractToolCalls(ChatResponse response) {
        if (response == null || response.getMessage() == null) return List.of();
        List<ToolUseBlock> blocks = response.getMessage().getToolUseBlocks();
        return blocks != null ? blocks : List.of();
    }

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

        String finishReason = "completed";
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
            blocks.add(new ToolUseBlock(id, toolNames.getOrDefault(id, ""), e.getValue()));
        }

        Msg msg = new AssistantMessage(blocks, null);
        return new ChatResponse(msg, new ChatUsage(0, 0), finishReason, null);
    }
}
