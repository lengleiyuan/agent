package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.agent.config.AgentExecutionConfig;
import cd.lan1akea.core.context.RuntimeContext;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.model.GenerateOptions;

import java.util.List;

/**
 * LoopContext 统一工厂。
 * <p>提供便捷方法从 {@link RuntimeContext}、{@link AgentExecutionConfig} 等构建
 * {@link LoopContext}，避免调用方手动组合 builder 调用。</p>
 */
public final class LoopContextFactory {

    private LoopContextFactory() {}

    /**
     * 创建 LoopContext 实例。
     * <p>从 {@code ctx} 中复制身份信息（requestId、tenantId、userId、sessionId、attributes），
     * 从 {@code execConfig} 中读取迭代次数与退避配置。</p>
     *
     * @param agentName  Agent 名称
     * @param ctx        运行时上下文
     * @param messages   初始消息列表
     * @param opts       生成选项
     * @param execConfig 执行配置（从中读取 maxIterations 和 iterationBackoffMs）
     * @param stream     是否流式执行
     * @return 构建完成的 LoopContext
     */
    public static LoopContext create(String agentName, RuntimeContext ctx,
                                      List<Msg> messages, GenerateOptions opts,
                                      AgentExecutionConfig execConfig, boolean stream) {
        return LoopContext.builder()
                .agentName(agentName)
                .fromRuntimeContext(ctx)
                .messages(messages)
                .generateOptions(opts)
                .maxIterations(execConfig.getMaxIterations())
                .backoffMs(execConfig.getIterationBackoffMs())
                .stream(stream)
                .build();
    }
}
