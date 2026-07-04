package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.agent.config.AgentExecutionConfig;
import cd.lan1akea.core.context.RuntimeContext;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.model.GenerateOptions;

import java.util.List;

/**
 * LoopContext 统一工厂。
 */
public final class LoopContextFactory {

    private LoopContextFactory() {}

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
