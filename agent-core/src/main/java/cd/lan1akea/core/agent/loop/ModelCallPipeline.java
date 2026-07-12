package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.hook.HookPipeline;
import cd.lan1akea.core.model.ChatModel;
import cd.lan1akea.core.model.ChatResponse;
import cd.lan1akea.core.model.ChatStreamChunk;
import cd.lan1akea.core.model.ToolSchema;
import cd.lan1akea.core.tool.ToolRegistry;
import cd.lan1akea.core.util.ChatResponseUtil;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

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

        return hookPipeline.aroundReasoning(ctx, schemas,
                event -> model.streamWithTools(
                        ctx.getMessages(), schemas, ctx.getGenerateOptions()));
    }

    /**
     * 非流式推理 —— 从流式派生。
     *
     * <p>收集 executeStream 的所有分块，调用 ChatResponseUtil.fromChunks 组装为单个 ChatResponse。
     *
     * @param ctx 循环上下文
     * @return 组装后的聊天响应
     */
    public Mono<ChatResponse> execute(LoopContext ctx) {
        return executeStream(ctx).collectList().map(ChatResponseUtil::fromChunks);
    }
}
