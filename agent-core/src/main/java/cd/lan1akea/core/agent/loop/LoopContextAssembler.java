package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.agent.config.AgentExecutionConfig;
import cd.lan1akea.core.context.RuntimeContext;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.model.GenerateOptions;

import java.util.List;

/**
 * LoopContext 装配器。
 *
 * <p>收拢 LoopContext 的完整构建逻辑，包括 GenerateOptions 映射和介入状态注入。
 * 调用方只需一行 {@code LoopContextAssembler.assemble(ctx, execConfig, sessionResult)}。
 */
public final class LoopContextAssembler {

    private LoopContextAssembler() {}

    /**
     * 从 RuntimeContext、AgentExecutionConfig 和会话加载结果构建 LoopContext。
     *
     * @param ctx           运行时上下文
     * @param execConfig    执行配置
     * @param sessionResult 会话加载结果（含消息和介入信息）
     * @return 构建完成的 LoopContext
     */
    public static LoopContext assemble(RuntimeContext ctx, AgentExecutionConfig execConfig,
                                        RequestPipeline.SessionLoadResult sessionResult) {
        LoopContext lc = LoopContext.builder()
                .agentName(ctx.getAgentName())
                .fromRuntimeContext(ctx)
                .messages(sessionResult.messages)
                .generateOptions(mapGenerateOptions(execConfig))
                .maxIterations(execConfig.getMaxIterations())
                .backoffMs(execConfig.getIterationBackoffMs())
                .build();
        applyIntervention(lc, sessionResult);
        return lc;
    }

    /**
     * 从执行配置映射生成选项。
     */
    private static GenerateOptions mapGenerateOptions(AgentExecutionConfig c) {
        return GenerateOptions.builder()
                .temperature(c.getTemperature())
                .maxTokens(c.getMaxTokens())
                .toolChoice(c.getToolChoice())
                .build();
    }

    /**
     * 将会话加载结果中的介入信息写入 LoopContext。
     */
    private static void applyIntervention(LoopContext lc, RequestPipeline.SessionLoadResult result) {
        if (result.interventionId != null) {
            lc.getInterventionState().setInterventionId(result.interventionId);
            lc.getInterventionState().setInterventionType(result.interventionType);
            lc.getInterventionState().setPausedToolArgs(result.pausedToolArgs);
        }
    }
}
