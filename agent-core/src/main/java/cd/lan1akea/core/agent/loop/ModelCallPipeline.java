package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.CoreConstants.FinishReason;
import cd.lan1akea.core.CoreConstants.HookSource;
import cd.lan1akea.core.exception.HookAbortException;
import cd.lan1akea.core.hook.AroundHookChain;
import cd.lan1akea.core.hook.HookContext;
import cd.lan1akea.core.hook.HookDispatcher;
import cd.lan1akea.core.hook.HookEvent;
import cd.lan1akea.core.hook.HookEventType;
import cd.lan1akea.core.hook.ReasoningEvent;
import cd.lan1akea.core.message.AssistantMessage;
import cd.lan1akea.core.message.ContentBlock;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.TextBlock;
import cd.lan1akea.core.message.ToolUseBlock;
import cd.lan1akea.core.metrics.AgentMetrics;
import cd.lan1akea.core.model.ChatModel;
import cd.lan1akea.core.model.ChatResponse;
import cd.lan1akea.core.model.ChatStreamChunk;
import cd.lan1akea.core.model.ChatUsage;
import cd.lan1akea.core.model.ToolSchema;
import cd.lan1akea.core.tool.ToolRegistry;
import cd.lan1akea.core.util.JsonUtils;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 推理 Hook 管线。
 * PRE_REASONING → PRE_MODEL → [aroundHook] model.stream → POST_MODEL → POST_REASONING
 * 流式为 canonical 实现，非流式由此派生。
 */
public class ModelCallPipeline {

    private final ChatModel model;
    private final HookDispatcher hookDispatcher;
    private final ToolRegistry toolRegistry;
    private final AroundHookChain aroundHookChain;
    private final AgentMetrics metrics;

    public ModelCallPipeline(ChatModel model, HookDispatcher hookDispatcher,
                              ToolRegistry toolRegistry, AroundHookChain aroundHookChain,
                              AgentMetrics metrics) {
        this.model = model;
        this.hookDispatcher = hookDispatcher;
        this.toolRegistry = toolRegistry;
        this.aroundHookChain = aroundHookChain;
        this.metrics = metrics;
    }

    /** 流式推理 — canonical */
    public Flux<ChatStreamChunk> executeStream(LoopContext ctx) {
        HookContext hc = buildHookContext(ctx);
        ReasoningEvent pre = new ReasoningEvent(HookEventType.PRE_REASONING);

        return hookDispatcher.dispatch(pre, hc)
                .flatMapMany(r -> {
                    if (r.isAbort()) {
                        return Flux.error(new HookAbortException(HookSource.HOOK, r.getAbortReason()));
                    }
                    if (r.isInterrupt()) {
                        ChatResponse ir = LoopDecisionEngine.buildInterruptedResponse(
                                r.getInterruptReason());
                        return Flux.just(chunkFromMessage(ir.getMessage(), FinishReason.INTERRUPTED));
                    }
                    if (pre.getBypassMessage() != null) {
                        String text = pre.getBypassMessage().getTextContent();
                        return Flux.just(chunkFromText(text != null ? text : "", FinishReason.STOP));
                    }
                    return callModelStream(ctx, hc, pre);
                });
    }

    /** 非流式推理 — 从流式派生 */
    public Mono<ChatResponse> execute(LoopContext ctx) {
        return executeStream(ctx).collectList().map(ModelCallPipeline::assembleResponseFromChunks);
    }

    // ---- 模型调用 ----

    private Flux<ChatStreamChunk> callModelStream(LoopContext ctx, HookContext hc, ReasoningEvent pre) {
        List<ToolSchema> schemas = toolRegistry.getSchemas(
                ctx.getTenantId(), ctx.getUserId(), ctx.getSessionId());

        return hookDispatcher.dispatch(new HookEvent(HookEventType.PRE_MODEL_CALL), hc)
                .flatMapMany(mr -> {
                    if (mr.isAbort()) {
                        return Flux.error(new HookAbortException(HookSource.MODEL, mr.getAbortReason()));
                    }
                    return aroundHookChain.aroundReasoningStream(pre, hc,
                            e -> {
                                final long start = System.currentTimeMillis();
                                return model.streamWithTools(
                                        ctx.getMessages(), schemas, ctx.getGenerateOptions())
                                        .doOnNext(chunk -> {
                                            if (chunk.getFinishReason() != null) {
                                                long latency = System.currentTimeMillis() - start;
                                                metrics.recordLlmCall(
                                                        model.getModelName(), model.getProvider(),
                                                        latency, 0, 0, true, null);
                                            }
                                        });
                            });
                })
                .concatWith(firePostModelHook(hc))
                .concatWith(firePostReasoningHook(hc));
    }

    private Flux<ChatStreamChunk> firePostModelHook(HookContext hc) {
        return hookDispatcher.dispatch(new HookEvent(HookEventType.POST_MODEL_CALL), hc)
                .then(Mono.<ChatStreamChunk>empty()).flux();
    }

    private Flux<ChatStreamChunk> firePostReasoningHook(HookContext hc) {
        return hookDispatcher.dispatch(new ReasoningEvent(HookEventType.POST_REASONING), hc)
                .then(Mono.<ChatStreamChunk>empty()).flux();
    }

    // ---- Chunk 组装 (static, shared with RequestPipeline) ----

    public static ChatResponse assembleResponseFromChunks(List<ChatStreamChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return null;

        StringBuilder text = new StringBuilder();
        Map<String, String> toolArgs = new LinkedHashMap<>();
        Map<String, String> toolNames = new LinkedHashMap<>();

        for (ChatStreamChunk chunk : chunks) {
            if (chunk.getDelta() != null && ChatStreamChunk.TYPE_TEXT.equals(chunk.getType())) {
                text.append(chunk.getDelta());
            }
            if (ChatStreamChunk.TYPE_TOOL_USE_START.equals(chunk.getType())
                    && chunk.getToolUseId() != null) {
                toolNames.put(chunk.getToolUseId(),
                        chunk.getToolName() != null ? chunk.getToolName() : "");
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
        if (text.length() > 0) blocks.add(new TextBlock(text.toString()));
        for (Map.Entry<String, String> e : toolArgs.entrySet()) {
            String id = e.getKey();
            blocks.add(new ToolUseBlock(id, toolNames.getOrDefault(id, ""),
                    JsonUtils.repairJson(e.getValue())));
        }

        Msg msg = new AssistantMessage(blocks, null);
        return new ChatResponse(msg, new ChatUsage(0, 0), finishReason, null);
    }

    // ---- 工具方法 ----

    private HookContext buildHookContext(LoopContext ctx) {
        return new HookContext(ctx.getAgentName(), ctx.getRequestId(),
                ctx.getTenantId(), ctx.getSessionId(),
                ctx.getUserId(), ctx.getIteration(),
                java.util.List.of(), ctx.getAttributes());
    }

    private static ChatStreamChunk chunkFromMessage(Msg msg, String finishReason) {
        return ChatStreamChunk.builder()
                .delta(msg.getTextContent())
                .finishReason(finishReason)
                .build();
    }

    private static ChatStreamChunk chunkFromText(String text, String finishReason) {
        return ChatStreamChunk.builder()
                .delta(text)
                .finishReason(finishReason)
                .build();
    }
}
