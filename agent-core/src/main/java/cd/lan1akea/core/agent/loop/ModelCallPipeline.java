package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.CoreConstants.FinishReason;
import cd.lan1akea.core.hook.HookPipeline;
import cd.lan1akea.core.message.AssistantMessage;
import cd.lan1akea.core.message.ContentBlock;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.TextBlock;
import cd.lan1akea.core.message.ToolUseBlock;
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
 * 模型调用管线。
 *
 * <p>负责与 LLM 交互并获取回复。Hook 编排全部委托给 {@link HookPipeline}，
 * 本类仅持有模型、工具 Schema 和指标收集器，专注模型调用本身。
 *
 * <p>流式为 canonical 实现，非流式通过 collectList + assembleResponseFromChunks 派生。
 */
public class ModelCallPipeline {

    /** 聊天模型，执行实际的 LLM 调用 */
    private final ChatModel model;
    /** Hook 管线门面，封装全部 Hook 分发 */
    private final HookPipeline hookPipeline;
    /** 工具注册表，获取当前上下文可用的工具 Schema */
    private final ToolRegistry toolRegistry;

    /**
     * 构建模型调用管线。
     *
     * @param model        聊天模型
     * @param hookPipeline Hook 管线门面
     * @param toolRegistry 工具注册表
     */
    public ModelCallPipeline(ChatModel model, HookPipeline hookPipeline,
                              ToolRegistry toolRegistry) {
        this.model = model;
        this.hookPipeline = hookPipeline;
        this.toolRegistry = toolRegistry;
    }

    /**
     * 流式推理 —— canonical 实现。
     *
     * <p>委托 {@link HookPipeline#aroundReasoning} 处理全部 Hook 编排
     * （PRE_REASONING  → aroundHook  → POST_REASONING），
     * 本方法只提供核心模型调用逻辑。
     *
     * @param ctx 循环上下文，包含消息列表和生成选项
     * @return 模型推理的流式分块
     */
    public Flux<ChatStreamChunk> executeStream(LoopContext ctx) {
        List<ToolSchema> schemas = toolRegistry.getSchemas(
                ctx.getTenantId(), ctx.getUserId(), ctx.getSessionId());

        return hookPipeline.aroundReasoning(ctx,
                event -> model.streamWithTools(
                        ctx.getMessages(), schemas, ctx.getGenerateOptions()));
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
}
