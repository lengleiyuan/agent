package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.agent.AgentEvent;
import cd.lan1akea.core.agent.AgentEventType;
import cd.lan1akea.core.event.EventBus;
import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.hook.recorder.HookRecorder;
import cd.lan1akea.core.memory.Memory;
import cd.lan1akea.core.memory.MemoryEntry;
import cd.lan1akea.core.memory.MemoryRetrievalQuery;
import cd.lan1akea.core.message.*;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.session.SessionId;
import cd.lan1akea.core.session.SessionStore;
import cd.lan1akea.core.session.SessionSummaryService;
import cd.lan1akea.core.session.ChatTurn;
import cd.lan1akea.core.tool.*;
import cd.lan1akea.core.util.IdGenerator;
import cd.lan1akea.core.util.JsonUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * ReAct 循环引擎。
 * <p>
 * 构建时注入所有只读子系统，每次请求通过 LoopContext（纯数据）驱动。
 * 负责：上下文压缩、记忆检索、Hook调度、LLM调用、工具执行、事件发射、会话持久化。
 * </p>
 */
public class ReActLoop {

    // === 构建时注入（只读，多请求共享） ===
    private final ChatModel model;
    private final ToolExecutor toolExecutor;
    private final HookDispatcher hookDispatcher;
    private final ToolRegistry toolRegistry;
    private final SessionStore sessionStore;
    private final SessionSummaryService summaryService;
    private final Memory memory;
    private final EventBus eventBus;
    private final HookRecorder hookRecorder;
    private final ModelContextWindow contextWindow;

    public ReActLoop(ChatModel model, ToolExecutor toolExecutor,
                      HookDispatcher hookDispatcher, ToolRegistry toolRegistry,
                      SessionStore sessionStore, SessionSummaryService summaryService,
                      Memory memory, EventBus eventBus, HookRecorder hookRecorder,
                      ModelContextWindow contextWindow) {
        this.model = model;
        this.toolExecutor = toolExecutor;
        this.hookDispatcher = hookDispatcher;
        this.toolRegistry = toolRegistry;
        this.sessionStore = sessionStore;
        this.summaryService = summaryService;
        this.memory = memory;
        this.eventBus = eventBus;
        this.hookRecorder = hookRecorder;
        this.contextWindow = contextWindow;
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
            // 上下文压缩
            compressIfNeeded(ctx);
            // 记忆检索
            enrichMemory(ctx);

            return reasoningStep(ctx)
                .onErrorResume(e -> handleError(ctx, e).then(Mono.error(e)))
                .flatMap(response -> {
                    ctx.setLastResponse(response);
                    if (response.getUsage() != null) {
                        ctx.addTokens(response.getUsage().getTotalTokens());
                    }
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
                .doOnNext(chunk -> {
                    HookEvent se = new HookEvent(HookEventType.ON_STREAM_CHUNK);
                    hookDispatcher.dispatch(HookEventType.ON_STREAM_CHUNK, se, hc).subscribe();
                });
        });
    }

    // ========================================================================
    // 上下文管理
    // ========================================================================

    /** 检查是否需要压缩，需要则执行 */
    void compressIfNeeded(LoopContext ctx) {
        if (contextWindow == null || summaryService == null) return;
        double usage = (double) ctx.getTotalTokens() / contextWindow.getMaxInputTokens();
        if (usage > 0.75 && ctx.getMessages().size() > 4) {
            int keep = 4;
            int removeCount = ctx.getMessages().size() - keep;
            if (removeCount <= 0) return;
            List<Msg> oldMsgs = new ArrayList<>(ctx.getMessages().subList(0, removeCount));
            ctx.getMessages().subList(0, removeCount).clear();
            // 摘要
            List<ChatTurn> turns = new ArrayList<>();
            for (int i = 0; i < oldMsgs.size(); i += 2) {
                String u = i < oldMsgs.size() ? oldMsgs.get(i).getTextContent() : "";
                String a = i + 1 < oldMsgs.size() ? oldMsgs.get(i + 1).getTextContent() : "";
                turns.add(new ChatTurn(0, 0, i / 2, u, a, null, LocalDateTime.now()));
            }
            Msg summary = summaryService.summarize(turns);
            ctx.getMessages().add(0, summary);
        }
    }

    /** 从长期记忆检索相关上下文 */
    void enrichMemory(LoopContext ctx) {
        if (memory == null || ctx.getTenantId() == null) return;
        String query = "";
        for (int i = ctx.getMessages().size() - 1; i >= 0; i--) {
            if (ctx.getMessages().get(i).getRole() == MsgRole.USER) {
                query = ctx.getMessages().get(i).getTextContent();
                break;
            }
        }
        if (query.isEmpty()) return;
        try {
            Long tid = Long.parseLong(ctx.getTenantId());
            Long uid = ctx.getUserId() != null ? Long.parseLong(ctx.getUserId()) : null;
            List<MemoryEntry> entries = memory.retrieve(
                new MemoryRetrievalQuery(query, 3, tid, uid))
                .collectList().block(Duration.ofSeconds(5));
            if (entries != null && !entries.isEmpty()) {
                StringBuilder sb = new StringBuilder("相关记忆:\n");
                for (MemoryEntry e : entries) sb.append("- ").append(e.getContent()).append("\n");
                ctx.getMessages().add(0, SystemMessage.of(sb.toString()));
            }
        } catch (Exception ignored) {}
    }

    // ========================================================================
    // 推理阶段
    // ========================================================================

    Mono<ChatResponse> reasoningStep(LoopContext ctx) {
        HookContext hc = buildHookContext(ctx);
        emitEvent(ctx, AgentEventType.REASONING_START);

        ReasoningEvent pre = new ReasoningEvent(HookEventType.PRE_REASONING);
        pre.setMessages(ctx.getMessages());
        record("PreReasoning", HookEventType.PRE_REASONING);

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
    // 观察阶段
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
        persistTurn(ctx);
        return Mono.just(ctx);
    }

    // ========================================================================
    // 持久化
    // ========================================================================

    void persistTurn(LoopContext ctx) {
        if (sessionStore == null || ctx.getSessionId() == null) return;
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

    // ========================================================================
    // 错误处理
    // ========================================================================

    Mono<Void> handleError(LoopContext ctx, Throwable error) {
        emitEvent(ctx, AgentEventType.ERROR);
        ErrorEvent ee = new ErrorEvent(error);
        return hookDispatcher.dispatch(HookEventType.ON_ERROR, ee, buildHookContext(ctx))
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
