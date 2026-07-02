package cd.lan1akea.metrics;

import cd.lan1akea.core.metrics.AgentMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/**
 * 基于 Micrometer 的 AgentMetrics 实现。
 * 指标通过 Prometheus 端点暴露（需配合 Spring Boot Actuator）。
 *
 * <p>使用方式：</p>
 * <pre>{@code
 * MeterRegistry registry = ...; // Spring 自动注入
 * AgentMetrics metrics = new MicrometerAgentMetrics(registry);
 * agent.setMetrics(metrics);
 * }</pre>
 */
public class MicrometerAgentMetrics implements AgentMetrics {

    /**
     * Micrometer 指标注册表
     */
    private final MeterRegistry registry;

    /**
     * LLM 调用延迟计时器
     */
    private final Timer llmCallTimer;
    /**
     * LLM 调用成功计数
     */
    private final Counter llmCallSuccess;
    /**
     * LLM 调用失败计数
     */
    private final Counter llmCallFailure;
    /**
     * Prompt token 消耗计数
     */
    private final Counter llmTokenPrompt;
    /**
     * Completion token 消耗计数
     */
    private final Counter llmTokenCompletion;

    /**
     * 工具调用延迟计时器
     */
    private final Timer toolCallTimer;
    /**
     * 工具调用成功计数
     */
    private final Counter toolCallSuccess;
    /**
     * 工具调用失败计数
     */
    private final Counter toolCallFailure;

    /**
     * ReAct 迭代次数计数
     */
    private final Counter iterationCounter;

    /**
     * 审批事件计数
     */
    private final Counter approvalCounter;

    /**
     * 创建 Micrometer 指标收集器。
     *
     * @param registry Micrometer 注册表
     */
    public MicrometerAgentMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.llmCallTimer = Timer.builder("agent.llm.call.duration")
            .description("LLM 调用延迟")
            .tag("type", "llm")
            .register(registry);
        this.llmCallSuccess = Counter.builder("agent.llm.call")
            .description("LLM 调用计数")
            .tag("result", "success")
            .register(registry);
        this.llmCallFailure = Counter.builder("agent.llm.call")
            .tag("result", "failure")
            .register(registry);
        this.llmTokenPrompt = Counter.builder("agent.llm.tokens")
            .description("Prompt token 消耗")
            .tag("direction", "prompt")
            .register(registry);
        this.llmTokenCompletion = Counter.builder("agent.llm.tokens")
            .tag("direction", "completion")
            .register(registry);

        this.toolCallTimer = Timer.builder("agent.tool.call.duration")
            .description("工具调用延迟")
            .register(registry);
        this.toolCallSuccess = Counter.builder("agent.tool.call")
            .tag("result", "success")
            .register(registry);
        this.toolCallFailure = Counter.builder("agent.tool.call")
            .tag("result", "failure")
            .register(registry);

        this.iterationCounter = Counter.builder("agent.loop.iterations")
            .description("ReAct 迭代次数")
            .register(registry);

        this.approvalCounter = Counter.builder("agent.approval")
            .description("审批事件计数")
            .register(registry);
    }

    /**
     * 记录 LLM 调用指标。
     */
    @Override
    public void recordLlmCall(String model, String provider, long latencyMs,
                               int promptTokens, int completionTokens,
                               boolean success, String errorType) {
        llmCallTimer.record(latencyMs, TimeUnit.MILLISECONDS);
        if (success) {
            llmCallSuccess.increment();
        } else {
            llmCallFailure.increment();
        }
        llmTokenPrompt.increment(promptTokens);
        llmTokenCompletion.increment(completionTokens);
    }

    /**
     * 记录工具调用指标。
     */
    @Override
    public void recordToolCall(String toolName, String riskLevel, long latencyMs,
                               boolean success, boolean approved, String errorType) {
        toolCallTimer.record(latencyMs, TimeUnit.MILLISECONDS);
        if (success) {
            toolCallSuccess.increment();
        } else {
            toolCallFailure.increment();
        }
    }

    /**
     * 记录 ReAct 迭代次数。
     */
    @Override
    public void recordIteration(String agentName, String sessionId,
                                int iteration, int toolCallsThisIteration) {
        iterationCounter.increment();
    }

    /**
     * 记录审批事件。
     */
    @Override
    public void recordApproval(String toolName, String sessionId, String decision) {
        approvalCounter.increment();
    }

    /**
     * 记录 Token 消耗。
     */
    @Override
    public void recordTokenUsage(String agentName, String sessionId, String model,
                                  int promptTokens, int completionTokens) {
        llmTokenPrompt.increment(promptTokens);
        llmTokenCompletion.increment(completionTokens);
    }
}
