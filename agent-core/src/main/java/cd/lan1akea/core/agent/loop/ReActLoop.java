package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.message.*;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.tool.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * ReAct 循环引擎。
 * <p>
 * 驱动 Reasoning（推理）→ Acting（工具调用）→ Observation（结果观察）循环。
 * 直到 LLM 不再返回工具调用或达到最大迭代次数。
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

    /**
     * 执行 ReAct 循环（非流式）。
     */
    public Mono<ChatResponse> execute(LoopContext ctx) {
        return Mono.defer(() -> {
            if (ctx.getIteration() >= ctx.getMaxIterations()) {
                return Mono.just(buildFinalResponse(ctx));
            }
            return reasoningStep(ctx)
                .flatMap(response -> {
                    ctx.setLastResponse(response);
                    List<ToolUseBlock> toolCalls = response.getMessage().getToolUseBlocks();
                    if (toolCalls.isEmpty()) {
                        return Mono.just(response); // 无工具调用，结束
                    }
                    return actingStep(ctx, toolCalls)
                        .flatMap(toolResults -> observationStep(ctx, toolResults))
                        .flatMap(updatedCtx -> execute(updatedCtx)); // 递归下一轮
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
                .collectList()
                .flatMapMany(chunks -> {
                    // 聚合流式结果
                    ctx.setIteration(ctx.getIteration() + 1);
                    return Flux.fromIterable(chunks);
                });
        });
    }

    // === 推理阶段 ===

    protected Mono<ChatResponse> reasoningStep(LoopContext ctx) {
        HookContext hookCtx = buildHookContext(ctx);

        // PreReasoning Hook
        ReasoningEvent preEvent = new ReasoningEvent(HookEventType.PRE_REASONING);
        preEvent.setMessages(ctx.getMessages());
        return hookDispatcher.dispatch(HookEventType.PRE_REASONING, preEvent, hookCtx)
            .flatMap(result -> {
                if (result.isAbort()) {
                    return Mono.error(new cd.lan1akea.core.exception.HookAbortException(
                        "hook", result.getAbortReason()));
                }
                if (result.isInterrupt()) {
                    return Mono.just(buildInterruptedResponse(result.getInterruptReason()));
                }
                // 调用 LLM
                List<ToolSchema> schemas = toolRegistry.getAllSchemas();
                return model.chatWithTools(ctx.getMessages(), schemas, ctx.getGenerateOptions());
            })
            .flatMap(response -> {
                // PostReasoning Hook
                ReasoningEvent postEvent = new ReasoningEvent(HookEventType.POST_REASONING);
                postEvent.setMessages(ctx.getMessages());
                return hookDispatcher.dispatch(HookEventType.POST_REASONING, postEvent, hookCtx)
                    .thenReturn(response);
            });
    }

    protected Flux<ChatStreamChunk> reasoningStepStream(LoopContext ctx) {
        HookContext hookCtx = buildHookContext(ctx);
        List<ToolSchema> schemas = toolRegistry.getAllSchemas();

        return hookDispatcher.dispatch(HookEventType.PRE_REASONING,
                new ReasoningEvent(HookEventType.PRE_REASONING), hookCtx)
            .thenMany(model.stream(ctx.getMessages(), ctx.getGenerateOptions()));
    }

    // === 行动阶段 ===

    protected Mono<List<ToolResult>> actingStep(LoopContext ctx, List<ToolUseBlock> toolCalls) {
        HookContext hookCtx = buildHookContext(ctx);

        ActingEvent actingEvent = new ActingEvent(HookEventType.PRE_ACTING);
        actingEvent.setToolCalls(toolCalls);
        return hookDispatcher.dispatch(HookEventType.PRE_ACTING, actingEvent, hookCtx)
            .flatMapMany(result -> Flux.fromIterable(toolCalls))
            .flatMap(toolCall -> executeSingleTool(toolCall, hookCtx))
            .collectList()
            .flatMap(results -> {
                ActingEvent postEvent = new ActingEvent(HookEventType.POST_ACTING);
                return hookDispatcher.dispatch(HookEventType.POST_ACTING, postEvent, hookCtx)
                    .thenReturn(results);
            });
    }

    protected Mono<ToolResult> executeSingleTool(ToolUseBlock toolCall, HookContext hookCtx) {
        ToolCallParam param = new ToolCallParam(
            toolCall.getId(), toolCall.getName(), toolCall.getArguments());

        HookEvent preEvent = new HookEvent(HookEventType.PRE_TOOL_CALL);
        return hookDispatcher.dispatch(HookEventType.PRE_TOOL_CALL, preEvent, hookCtx)
            .flatMap(result -> {
                if (result.isAbort()) {
                    return Mono.just(ToolResult.failure(
                        "工具调用被 Hook 阻止: " + result.getAbortReason()));
                }
                return toolExecutor.execute(param);
            })
            .flatMap(toolResult -> {
                HookEvent postEvent = new HookEvent(HookEventType.POST_TOOL_CALL);
                return hookDispatcher.dispatch(HookEventType.POST_TOOL_CALL, postEvent, hookCtx)
                    .thenReturn(toolResult);
            });
    }

    // === 观察阶段 ===

    protected Mono<LoopContext> observationStep(LoopContext ctx, List<ToolResult> toolResults) {
        // 将工具结果追加到消息历史
        for (int i = 0; i < toolResults.size(); i++) {
            ToolResult result = toolResults.get(i);
            ToolUseBlock toolCall = ctx.getLastResponse().getMessage().getToolUseBlocks().get(i);
            Msg resultMsg = Msg.builder(MsgRole.TOOL)
                .addToolResult(toolCall.getId(),
                    result.isSuccess() ? result.getContent() : "[错误] " + result.getErrorMessage(),
                    !result.isSuccess())
                .build();
            ctx.addMessage(resultMsg);
        }

        ctx.setIteration(ctx.getIteration() + 1);
        return Mono.just(ctx);
    }

    // === 辅助方法 ===

    protected HookContext buildHookContext(LoopContext ctx) {
        return new HookContext(
            ctx.getAgentName(), null, null, null,
            ctx.getIteration(), null, null);
    }

    protected ChatResponse buildFinalResponse(LoopContext ctx) {
        return ctx.getLastResponse();
    }

    protected ChatResponse buildInterruptedResponse(String interruptReason) {
        AssistantMessage msg = (AssistantMessage) Msg.builder(MsgRole.ASSISTANT)
            .addText("[执行已中断: " + interruptReason + "]")
            .putMetadata(MessageMetadataKeys.INTERRUPT_ID, interruptReason)
            .build();
        return new ChatResponse(msg, new ChatUsage(0, 0), "interrupted", "");
    }
}
