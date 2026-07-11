package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.CoreConstants.FinishReason;
import cd.lan1akea.core.CoreConstants.HookSource;
import cd.lan1akea.core.exception.HookAbortException;
import cd.lan1akea.core.hook.AroundHookChain;
import cd.lan1akea.core.hook.HookContext;
import cd.lan1akea.core.hook.HookDispatcher;
import cd.lan1akea.core.hook.HookEvent;
import cd.lan1akea.core.hook.HookEventType;

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
 *
 * <p>将模型调用和 Hook 分发组合为可复用的管线：
 * PRE_REASONING → PRE_MODEL → [aroundHook] model.stream → POST_MODEL → POST_REASONING
 *
 * <p>流式为 canonical 实现，非流式通过 collectList + assembleResponseFromChunks 派生。
 * 支持 KB 旁路（PreReasoningHook 设置 bypassMessage）、中断和异常处理。
 */
public class ModelCallPipeline {

    /** 聊天模型，执行实际的 LLM 调用 */
    private final ChatModel model;
    /** Hook 分发器，在推理各阶段触发回调 */
    private final HookDispatcher hookDispatcher;
    /** 工具注册表，获取当前上下文可用的工具 Schema */
    private final ToolRegistry toolRegistry;
    /** AroundHook 链，以洋葱模式包裹模型调用 */
    private final AroundHookChain aroundHookChain;
    /** 指标收集器，记录 LLM 调用延迟 */
    private final AgentMetrics metrics;

    /**
     * 构建推理管线。
     *
     * @param model           聊天模型
     * @param hookDispatcher  Hook 分发器
     * @param toolRegistry    工具注册表
     * @param aroundHookChain AroundHook 链
     * @param metrics         指标收集器
     */
    public ModelCallPipeline(ChatModel model, HookDispatcher hookDispatcher,
                              ToolRegistry toolRegistry, AroundHookChain aroundHookChain,
                              AgentMetrics metrics) {
        this.model = model;
        this.hookDispatcher = hookDispatcher;
        this.toolRegistry = toolRegistry;
        this.aroundHookChain = aroundHookChain;
        this.metrics = metrics;
    }

    /**
     * 流式推理 —— canonical 实现。
     *
     * <p>依次分发 PRE_REASONING Hook → 调用模型 → 流式返回分块。
     * 支持三种提前终止路径：abort（终止）、interrupt（中断）、KB bypass（旁路模型）。
     *
     * @param ctx 循环上下文，包含消息列表和生成选项
     * @return 模型推理的流式分块
     */
    public Flux<ChatStreamChunk> executeStream(LoopContext ctx) {
        HookContext hc = ctx.toHookContext();
        HookEvent pre = new HookEvent(HookEventType.PRE_REASONING);
        pre.setMessages(ctx.getMessages());

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
                        return Flux.just(ChatStreamChunk.of(text != null ? text : "", FinishReason.STOP));
                    }
                    return callModelStream(ctx, hc, pre);
                });
    }

    /**
     * 非流式推理 —— 从流式派生。
     *
     * <p>收集 executeStream 的所有分块，调用 assembleResponseFromChunks 组装为单个 ChatResponse。
     *
     * @param ctx 循环上下文
     * @return 组装后的聊天响应
     */
    public Mono<ChatResponse> execute(LoopContext ctx) {
        return executeStream(ctx).collectList().map(ModelCallPipeline::assembleResponseFromChunks);
    }

    /**
     * 执行实际的模型调用，包裹在 Hook 管线中。
     *
     * <p>流程：PRE_MODEL_CALL Hook → aroundHook 洋葱包裹 → model.streamWithTools → 记录延迟指标
     * → POST_MODEL_CALL Hook → POST_REASONING Hook
     *
     * @param ctx  循环上下文
     * @param hc   Hook 上下文
     * @param pre  预推理事件（传递给 aroundHook）
     * @return 模型流式输出的分块序列
     */
    private Flux<ChatStreamChunk> callModelStream(LoopContext ctx, HookContext hc, HookEvent pre) {
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

    /**
     * 触发 POST_MODEL_CALL Hook。
     *
     * <p>fire-and-forget 模式：Hook 结果不影响主流程，仅用于日志/审计。
     *
     * @param hc Hook 上下文
     * @return 空 Flux（不发射元素）
     */
    private Flux<ChatStreamChunk> firePostModelHook(HookContext hc) {
        return hookDispatcher.dispatch(new HookEvent(HookEventType.POST_MODEL_CALL), hc)
                .then(Mono.<ChatStreamChunk>empty()).flux();
    }

    /**
     * 触发 POST_REASONING Hook。
     *
     * <p>fire-and-forget 模式：Hook 结果不影响主流程，仅用于日志/审计。
     *
     * @param hc Hook 上下文
     * @return 空 Flux（不发射元素）
     */
    private Flux<ChatStreamChunk> firePostReasoningHook(HookContext hc) {
        return hookDispatcher.dispatch(new HookEvent(HookEventType.POST_REASONING), hc)
                .then(Mono.<ChatStreamChunk>empty()).flux();
    }

    /**
     * 从流式分块列表组装单个 ChatResponse。
     *
     * <p>聚合文本增量（text）和工具调用分块（tool_use_start/delta）为完整响应。
     * 工具参数 JSON 经过 repairJson 修复常见 LLM 格式错误。
     * 此方法为静态方法，同时供 RequestPipeline 的非流式路径使用。
     *
     * @param chunks 流式分块列表
     * @return 组装后的聊天响应，chunks 为空时返回 null
     */
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

        String finishReason = null;
        for (int i = chunks.size() - 1; i >= 0; i--) {
            if (chunks.get(i).getFinishReason() != null) {
                finishReason = chunks.get(i).getFinishReason();
                break;
            }
        }
        if (finishReason == null) finishReason = FinishReason.COMPLETED;

        List<ContentBlock> blocks = new ArrayList<>();
    if (!text.isEmpty()) {
            blocks.add(new TextBlock(text.toString()));
        }
        for (Map.Entry<String, String> e : toolArgs.entrySet()) {
            String id = e.getKey();
            blocks.add(new ToolUseBlock(id, toolNames.getOrDefault(id, ""),
                    JsonUtils.repairJson(e.getValue())));
        }

        Msg msg = new AssistantMessage(blocks, null);
        return new ChatResponse(msg, new ChatUsage(0, 0), finishReason, null);
    }

    /**
     * 从循环上下文构建 Hook 上下文。
     *
     * @param ctx 循环上下文
     * @return 新的 Hook 上下文
     */
    /**
     * 从消息创建流式分块（委托给 chunkFromText 处理 null 安全）。
     *
     * @param msg          消息
     * @param finishReason 完成原因
     * @return 流式分块
     */
    private static ChatStreamChunk chunkFromMessage(Msg msg, String finishReason) {
        return ChatStreamChunk.of(msg.getTextContent(), finishReason);
    }
}
