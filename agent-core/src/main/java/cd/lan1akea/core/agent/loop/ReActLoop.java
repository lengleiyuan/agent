package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.agent.AgentEvent;
import cd.lan1akea.core.agent.AgentEventType;
import cd.lan1akea.core.event.EventBus;
import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.hook.recorder.HookRecorder;
import cd.lan1akea.core.message.*;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.session.SessionId;
import cd.lan1akea.core.session.SessionStore;
import cd.lan1akea.core.session.ChatTurn;
import cd.lan1akea.core.tool.*;
import cd.lan1akea.core.util.IdGenerator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * ReAct 循环引擎。
 * <p>
 * 构建时注入只读服务，每次请求通过 LoopContext（纯数据）驱动。
 * 只负责：Hook调度 → LLM调用 → 工具执行 → 结果观察 → 会话持久化。
 * 上下文压缩和记忆检索通过 PreReasoningHook 实现，不硬编码在此。
 * </p>
 */
public class ReActLoop {

    private final ChatModel model;
    private final ToolExecutor toolExecutor;
    private final HookDispatcher hookDispatcher;
    private final ToolRegistry toolRegistry;
    private final SessionStore sessionStore;
    private final EventBus eventBus;
    private final HookRecorder hookRecorder;

    public ReActLoop(ChatModel model, ToolExecutor toolExecutor,
                      HookDispatcher hookDispatcher, ToolRegistry toolRegistry,
                      SessionStore sessionStore, EventBus eventBus, HookRecorder hookRecorder) {
        this.model = model;
        this.toolExecutor = toolExecutor;
        this.hookDispatcher = hookDispatcher;
        this.toolRegistry = toolRegistry;
        this.sessionStore = sessionStore;
        this.eventBus = eventBus;
        this.hookRecorder = hookRecorder;
    }

    // ========================================================================
    // 主循环
    // ========================================================================

    public Mono<ChatResponse> execute(LoopContext ctx) {
        return Mono.defer(() -> {
            if (ctx.getIteration() >= ctx.getMaxIterations()) {
                emitEvent(ctx, AgentEventType.COMPLETED);
                return Mono.justOrEmpty(ctx.getLastResponse());
            }
            return reasoningStep(ctx)
                .onErrorResume(e -> handleError(ctx, e).then(Mono.error(e)))
                .flatMap(response -> {
                    ctx.setLastResponse(response);
                    if (response.getUsage() != null) ctx.addTokens(response.getUsage().getTotalTokens());
                    List<ToolUseBlock> toolCalls = response.getMessage().getToolUseBlocks();
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

    public Flux<ChatStreamChunk> executeStream(LoopContext ctx) {
        return Flux.defer(() -> {
            if (ctx.getIteration() >= ctx.getMaxIterations()) return Flux.empty();
            HookContext hc = buildHookContext(ctx);
            return hookDispatcher.dispatch(HookEventType.PRE_REASONING,
                    new ReasoningEvent(HookEventType.PRE_REASONING), hc)
                .thenMany(model.stream(ctx.getMessages(), ctx.getGenerateOptions()))
                .doOnNext(chunk ->
                    hookDispatcher.dispatch(HookEventType.ON_STREAM_CHUNK,
                        new HookEvent(HookEventType.ON_STREAM_CHUNK), hc).subscribe());
        });
    }

    // ========================================================================
    // 推理阶段（PreReasoning Hooks 在此处自动执行压缩/记忆检索）
    // ========================================================================

    Mono<ChatResponse> reasoningStep(LoopContext ctx) {
        HookContext hc = buildHookContext(ctx);
        emitEvent(ctx, AgentEventType.REASONING_START);

        ReasoningEvent pre = new ReasoningEvent(HookEventType.PRE_REASONING);
        pre.setMessages(ctx.getMessages());
        record("PreReasoning", HookEventType.PRE_REASONING);

        // PreReasoning Hook 链：自动执行 ContextCompressionHook → MemoryEnrichmentHook → 其他
        return hookDispatcher.dispatch(HookEventType.PRE_REASONING, pre, hc)
            .flatMap(r -> {
                if (r.isAbort()) return Mono.error(
                    new cd.lan1akea.core.exception.HookAbortException("hook", r.getAbortReason()));
                if (r.isInterrupt()) return Mono.just(buildInterrupted(r.getInterruptReason()));
                List<ToolSchema> schemas = toolRegistry.getSchemasForTenant(ctx.getTenantId());
                return model.chatWithTools(ctx.getMessages(), schemas, ctx.getGenerateOptions());
            })
            .flatMap(resp -> {
                emitEvent(ctx, AgentEventType.REASONING_END);
                ReasoningEvent post = new ReasoningEvent(HookEventType.POST_REASONING);
                record("PostReasoning", HookEventType.POST_REASONING);
                return hookDispatcher.dispatch(HookEventType.POST_REASONING, post, hc)
                    .thenReturn(resp);
            });
    }

    // ========================================================================
    // 行动阶段
    // ========================================================================

    Mono<List<ToolResult>> actingStep(LoopContext ctx, List<ToolUseBlock> toolCalls) {
        HookContext hc = buildHookContext(ctx);
        emitEvent(ctx, AgentEventType.ACTING_START);
        ActingEvent ae = new ActingEvent(HookEventType.PRE_ACTING);
        ae.setToolCalls(toolCalls);
        record("PreActing", HookEventType.PRE_ACTING);

        return hookDispatcher.dispatch(HookEventType.PRE_ACTING, ae, hc)
            .flatMapMany(r -> Flux.fromIterable(toolCalls))
            .flatMap(tc -> executeSingleTool(ctx, tc, hc))
            .collectList()
            .flatMap(results -> {
                emitEvent(ctx, AgentEventType.ACTING_END);
                ActingEvent post = new ActingEvent(HookEventType.POST_ACTING);
                record("PostActing", HookEventType.POST_ACTING);
                return hookDispatcher.dispatch(HookEventType.POST_ACTING, post, hc)
                    .thenReturn(results);
            });
    }

    Mono<ToolResult> executeSingleTool(LoopContext ctx, ToolUseBlock tc, HookContext hc) {
        ToolCallParam param = new ToolCallParam(tc.getId(), tc.getName(), tc.getArguments());
        record("PreToolCall:" + tc.getName(), HookEventType.PRE_TOOL_CALL);

        return hookDispatcher.dispatch(HookEventType.PRE_TOOL_CALL,
                new HookEvent(HookEventType.PRE_TOOL_CALL), hc)
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
                record("PostToolCall:" + tc.getName(), HookEventType.POST_TOOL_CALL);
                return hookDispatcher.dispatch(HookEventType.POST_TOOL_CALL,
                    new HookEvent(HookEventType.POST_TOOL_CALL), hc).thenReturn(result);
            });
    }

    // ========================================================================
    // 观察 + 持久化
    // ========================================================================

    Mono<LoopContext> observationStep(LoopContext ctx, List<ToolResult> results) {
        for (int i = 0; i < results.size(); i++) {
            ToolResult r = results.get(i);
            ToolUseBlock tc = ctx.getLastResponse().getMessage().getToolUseBlocks().get(i);
            ctx.addMessage(Msg.builder(MsgRole.TOOL)
                .addToolResult(tc.getId(),
                    r.isSuccess() ? r.getContent() : "[错误] " + r.getErrorMessage(),
                    !r.isSuccess()).build());
        }
        ctx.setIteration(ctx.getIteration() + 1);

        if (sessionStore != null && ctx.getSessionId() != null) {
            Msg userMsg = null, assistantMsg = null;
            for (Msg m : ctx.getMessages()) {
                if (m.getRole() == MsgRole.USER) userMsg = m;
                if (m.getRole() == MsgRole.ASSISTANT) assistantMsg = m;
            }
            ChatTurn turn = new ChatTurn(IdGenerator.nextId(),
                Long.parseLong(ctx.getSessionId()), ctx.getIteration(),
                userMsg != null ? userMsg.getTextContent() : "",
                assistantMsg != null ? assistantMsg.getTextContent() : null,
                null, LocalDateTime.now());
            sessionStore.addTurn(new SessionId(ctx.getSessionId()), turn).subscribe();
        }
        return Mono.just(ctx);
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
            ctx.getUserId(), ctx.getIteration(), new ArrayList<>(), null);
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

    void record(String name, HookEventType type) {
        if (hookRecorder != null) hookRecorder.record(name, new HookEvent(type), HookResult.continue_());
    }
}
