package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.agent.AgentEvent;
import cd.lan1akea.core.agent.AgentEventType;
import cd.lan1akea.core.event.EventBus;
import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.hook.recorder.HookRecorder;
import cd.lan1akea.core.message.*;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.tool.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * ReAct 循环引擎（完整版）。
 * <p>
 * 完整执行流程：上下文压缩 → 记忆检索 → PreReasoningHook → LLM调用 → PostReasoningHook
 * → 权限校验 → PreActingHook → 逐个工具调用（PreToolCallHook → 执行 → PostToolCallHook）
 * → PostActingHook → 观察 → 会话持久化 → 事件发射 → 递归下一轮。
 * </p>
 * <p>
 * 错误处理：ErrorHook（所有异常）、InterruptHook（工具暂停审批）。
 * </p>
 */
public class ReActLoop {

    private final ChatModel model;
    private final ToolExecutor toolExecutor;
    private final HookDispatcher hookDispatcher;
    private final ToolRegistry toolRegistry;

    public ReActLoop(ChatModel model, ToolExecutor toolExecutor,
                      HookDispatcher hookDispatcher, ToolRegistry toolRegistry) {
        this.model = model;
        this.toolExecutor = toolExecutor;
        this.hookDispatcher = hookDispatcher;
        this.toolRegistry = toolRegistry;
    }

    // ========================================================================
    // 主循环入口
    // ========================================================================

    /**
     * 执行 ReAct 循环（非流式）。
     */
    public Mono<ChatResponse> execute(LoopContext ctx) {
        return Mono.defer(() -> {
            if (ctx.getIteration() >= ctx.getMaxIterations()) {
                emitAgentEvent(ctx, AgentEventType.COMPLETED);
                return Mono.just(buildFinalResponse(ctx));
            }

            // 上下文压缩检查
            if (ctx.needsCompression()) {
                ctx.compressHistory();
            }

            return reasoningStep(ctx)
                .onErrorResume(e -> handleError(ctx, e).then(Mono.error(e)))
                .flatMap(response -> {
                    ctx.setLastResponse(response);
                    // Token 追踪
                    if (response.getUsage() != null) {
                        ctx.addTokens(response.getUsage().getTotalTokens());
                    }

                    List<ToolUseBlock> toolCalls = response.getMessage().getToolUseBlocks();
                    if (toolCalls.isEmpty()) {
                        // 无工具调用，结束循环
                        emitAgentEvent(ctx, AgentEventType.COMPLETED);
                        return Mono.just(response);
                    }
                    return actingStep(ctx, toolCalls)
                        .flatMap(toolResults -> observationStep(ctx, toolResults))
                        .flatMap(this::execute); // 递归下一轮
                });
        });
    }

    /**
     * 执行 ReAct 循环（流式）。
     */
    public Flux<ChatStreamChunk> executeStream(LoopContext ctx) {
        return Flux.defer(() -> {
            if (ctx.getIteration() >= ctx.getMaxIterations()) {
                return Flux.empty();
            }
            return reasoningStepStream(ctx)
                .doOnNext(chunk -> {
                    // 流式块经过 StreamingChunkHook
                    HookContext hookCtx = buildHookContext(ctx);
                    HookEvent se = new HookEvent(HookEventType.ON_STREAM_CHUNK);
                    hookDispatcher.dispatch(HookEventType.ON_STREAM_CHUNK, se, hookCtx).subscribe();
                })
                .collectList()
                .flatMapMany(chunks -> {
                    ctx.setIteration(ctx.getIteration() + 1);
                    return Flux.fromIterable(chunks);
                });
        });
    }

    // ========================================================================
    // 推理阶段（Reasoning）
    // ========================================================================

    protected Mono<ChatResponse> reasoningStep(LoopContext ctx) {
        HookContext hookCtx = buildHookContext(ctx);
        emitAgentEvent(ctx, AgentEventType.REASONING_START);

        // 从长期记忆检索相关上下文
        String lastUserMsg = getLastUserMessage(ctx);
        if (lastUserMsg != null && !lastUserMsg.isEmpty()) {
            ctx.enrichFromMemory(lastUserMsg);
        }

        // PreReasoning Hook
        ReasoningEvent preEvent = new ReasoningEvent(HookEventType.PRE_REASONING);
        preEvent.setMessages(ctx.getMessages());
        recordHook(ctx, "PreReasoning", HookEventType.PRE_REASONING);

        return hookDispatcher.dispatch(HookEventType.PRE_REASONING, preEvent, hookCtx)
            .flatMap(result -> {
                recordHookResult(ctx, "PreReasoning", result);
                if (result.isAbort()) {
                    return Mono.error(new cd.lan1akea.core.exception.HookAbortException(
                        "hook", result.getAbortReason()));
                }
                if (result.isInterrupt()) {
                    return Mono.just(buildInterruptedResponse(result.getInterruptReason()));
                }
                // 调用 LLM
                List<ToolSchema> schemas = toolRegistry.getSchemasForTenant(ctx.getTenantId());
                return model.chatWithTools(ctx.getMessages(), schemas, ctx.getGenerateOptions());
            })
            .flatMap(response -> {
                emitAgentEvent(ctx, AgentEventType.REASONING_END);
                // PostReasoning Hook
                ReasoningEvent postEvent = new ReasoningEvent(HookEventType.POST_REASONING);
                postEvent.setMessages(ctx.getMessages());
                recordHook(ctx, "PostReasoning", HookEventType.POST_REASONING);
                return hookDispatcher.dispatch(HookEventType.POST_REASONING, postEvent, hookCtx)
                    .flatMap(postResult -> {
                        recordHookResult(ctx, "PostReasoning", postResult);
                        if (postResult.isAbort()) {
                            return Mono.error(new cd.lan1akea.core.exception.HookAbortException(
                                "hook", postResult.getAbortReason()));
                        }
                        return Mono.just(response);
                    });
            });
    }

    protected Flux<ChatStreamChunk> reasoningStepStream(LoopContext ctx) {
        HookContext hookCtx = buildHookContext(ctx);
        List<ToolSchema> schemas = toolRegistry.getSchemasForTenant(ctx.getTenantId());
        ReasoningEvent preEvent = new ReasoningEvent(HookEventType.PRE_REASONING);
        return hookDispatcher.dispatch(HookEventType.PRE_REASONING, preEvent, hookCtx)
            .thenMany(model.stream(ctx.getMessages(), ctx.getGenerateOptions()));
    }

    // ========================================================================
    // 行动阶段（Acting）
    // ========================================================================

    protected Mono<List<ToolResult>> actingStep(LoopContext ctx, List<ToolUseBlock> toolCalls) {
        HookContext hookCtx = buildHookContext(ctx);
        emitAgentEvent(ctx, AgentEventType.ACTING_START);

        // PreActing Hook
        ActingEvent actingEvent = new ActingEvent(HookEventType.PRE_ACTING);
        actingEvent.setToolCalls(toolCalls);
        recordHook(ctx, "PreActing", HookEventType.PRE_ACTING);

        return hookDispatcher.dispatch(HookEventType.PRE_ACTING, actingEvent, hookCtx)
            .flatMapMany(result -> {
                recordHookResult(ctx, "PreActing", result);
                if (result.isAbort()) {
                    return Flux.error(new cd.lan1akea.core.exception.HookAbortException(
                        "hook", result.getAbortReason()));
                }
                return Flux.fromIterable(toolCalls);
            })
            .flatMap(toolCall -> executeSingleTool(ctx, toolCall, hookCtx))
            .collectList()
            .flatMap(results -> {
                emitAgentEvent(ctx, AgentEventType.ACTING_END);
                // PostActing Hook
                ActingEvent postEvent = new ActingEvent(HookEventType.POST_ACTING);
                recordHook(ctx, "PostActing", HookEventType.POST_ACTING);
                return hookDispatcher.dispatch(HookEventType.POST_ACTING, postEvent, hookCtx)
                    .flatMap(postResult -> {
                        recordHookResult(ctx, "PostActing", postResult);
                        return Mono.just(results);
                    });
            });
    }

    protected Mono<ToolResult> executeSingleTool(LoopContext ctx, ToolUseBlock toolCall,
                                                   HookContext hookCtx) {
        ToolCallParam param = new ToolCallParam(
            toolCall.getId(), toolCall.getName(), toolCall.getArguments());

        // PreToolCall Hook
        recordHook(ctx, "PreToolCall:" + toolCall.getName(), HookEventType.PRE_TOOL_CALL);
        HookEvent preEvent = new HookEvent(HookEventType.PRE_TOOL_CALL);

        return hookDispatcher.dispatch(HookEventType.PRE_TOOL_CALL, preEvent, hookCtx)
            .flatMap(result -> {
                recordHookResult(ctx, "PreToolCall", result);
                if (result.isAbort()) {
                    return Mono.just(ToolResult.failure(
                        "工具调用被 Hook 阻止: " + result.getAbortReason()));
                }
                // 权限校验已在 ToolExecutor 中完成
                return toolExecutor.execute(param, ctx.getTenantId());
            })
            .onErrorResume(ToolSuspendException.class, e -> {
                // 工具暂停 → InterruptHook
                InterruptEvent ie = new InterruptEvent(e.getQuestion(), toolCall.getName());
                return hookDispatcher.dispatch(HookEventType.ON_INTERRUPT, ie, hookCtx)
                    .flatMap(ir -> {
                        if (ir.isAbort()) {
                            return Mono.just(ToolResult.failure("操作被拒绝: " + ir.getAbortReason()));
                        }
                        return Mono.just(ToolResult.failure("等待人工审批: " + e.getQuestion()));
                    });
            })
            .flatMap(toolResult -> {
                // PostToolCall Hook
                recordHook(ctx, "PostToolCall:" + toolCall.getName(), HookEventType.POST_TOOL_CALL);
                HookEvent postEvent = new HookEvent(HookEventType.POST_TOOL_CALL);
                return hookDispatcher.dispatch(HookEventType.POST_TOOL_CALL, postEvent, hookCtx)
                    .flatMap(postResult -> {
                        recordHookResult(ctx, "PostToolCall", postResult);
                        return Mono.just(toolResult);
                    });
            });
    }

    // ========================================================================
    // 观察阶段（Observation）+ 持久化
    // ========================================================================

    protected Mono<LoopContext> observationStep(LoopContext ctx, List<ToolResult> toolResults) {
        List<Msg> responseMsgs = new ArrayList<>();
        for (int i = 0; i < toolResults.size(); i++) {
            ToolResult result = toolResults.get(i);
            ToolUseBlock toolCall = ctx.getLastResponse().getMessage().getToolUseBlocks().get(i);
            Msg resultMsg = Msg.builder(MsgRole.TOOL)
                .addToolResult(toolCall.getId(),
                    result.isSuccess() ? result.getContent()
                        : "[错误] " + result.getErrorMessage(),
                    !result.isSuccess())
                .build();
            ctx.addMessage(resultMsg);
            responseMsgs.add(resultMsg);
        }

        ctx.setIteration(ctx.getIteration() + 1);

        // 持久化当前轮次
        if (ctx.getSessionId() != null && ctx.getLastResponse() != null) {
            ctx.persistTurn(
                getLastUserMessageObj(ctx),
                ctx.getLastResponse().getMessage(),
                cd.lan1akea.core.util.JsonUtils.toCompactJson(responseMsgs));
        }

        return Mono.just(ctx);
    }

    // ========================================================================
    // 错误处理
    // ========================================================================

    /**
     * 统一的错误处理：触发 ErrorHook，发射 AgentEvent.ERROR。
     */
    protected Mono<Void> handleError(LoopContext ctx, Throwable error) {
        emitAgentEvent(ctx, AgentEventType.ERROR);
        HookContext hookCtx = buildHookContext(ctx);
        ErrorEvent ee = new ErrorEvent(error);
        recordHook(ctx, "ErrorHook", HookEventType.ON_ERROR);
        return hookDispatcher.dispatch(HookEventType.ON_ERROR, ee, hookCtx)
            .flatMap(result -> {
                recordHookResult(ctx, "ErrorHook", result);
                if (result.isAbort()) {
                    return Mono.error(new cd.lan1akea.core.exception.HookAbortException(
                        "ErrorHook", result.getAbortReason()));
                }
                return Mono.empty();
            });
    }

    // ========================================================================
    // 辅助方法
    // ========================================================================

    protected HookContext buildHookContext(LoopContext ctx) {
        return new HookContext(
            ctx.getAgentName(),
            ctx.getTenantId(),
            ctx.getSessionId(),
            ctx.getUserId(),
            ctx.getIteration(),
            new ArrayList<>(),
            null);
    }

    protected ChatResponse buildFinalResponse(LoopContext ctx) {
        return ctx.getLastResponse();
    }

    protected ChatResponse buildInterruptedResponse(String interruptReason) {
        Msg msg = Msg.builder(MsgRole.ASSISTANT)
            .addText("[执行已中断: " + interruptReason + "]")
            .putMetadata(MessageMetadataKeys.INTERRUPT_ID, interruptReason)
            .build();
        return new ChatResponse(msg, new ChatUsage(0, 0), "interrupted", "");
    }

    /** 发射 Agent 生命周期事件 */
    protected void emitAgentEvent(LoopContext ctx, AgentEventType type) {
        EventBus bus = ctx.getEventBus();
        if (bus != null) {
            bus.publish(new AgentEvent(type, ctx.getAgentName())).subscribe();
        }
    }

    /** 记录 Hook 执行 */
    protected void recordHook(LoopContext ctx, String hookName, HookEventType eventType) {
        HookRecorder recorder = ctx.getHookRecorder();
        if (recorder != null) {
            recorder.record(hookName, new HookEvent(eventType), HookResult.continue_());
        }
    }

    protected void recordHookResult(LoopContext ctx, String hookName, HookResult result) {
        HookRecorder recorder = ctx.getHookRecorder();
        if (recorder != null) {
            recorder.record(hookName, new HookEvent(HookEventType.PRE_REASONING), result);
        }
    }

    /** 获取最后一条用户消息文本 */
    protected String getLastUserMessage(LoopContext ctx) {
        for (int i = ctx.getMessages().size() - 1; i >= 0; i--) {
            Msg msg = ctx.getMessages().get(i);
            if (msg.getRole() == MsgRole.USER) {
                return msg.getTextContent();
            }
        }
        return null;
    }

    protected Msg getLastUserMessageObj(LoopContext ctx) {
        for (int i = ctx.getMessages().size() - 1; i >= 0; i--) {
            Msg msg = ctx.getMessages().get(i);
            if (msg.getRole() == MsgRole.USER) {
                return msg;
            }
        }
        return null;
    }
}
