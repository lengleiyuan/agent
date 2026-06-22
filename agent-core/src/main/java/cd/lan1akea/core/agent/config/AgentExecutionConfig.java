package cd.lan1akea.core.agent.config;

import cd.lan1akea.core.model.ToolChoicePolicy;

/**
 * Agent 执行配置。
 * <p>
 * 控制 ReAct 循环的行为参数。
 * </p>
 */
public class AgentExecutionConfig {

    /** 最大 ReAct 迭代次数 */
    private final int maxIterations;

    /** 温度 */
    private final double temperature;

    /** 最大输出 Token */
    private final int maxTokens;

    /** 工具选择策略 */
    private final ToolChoicePolicy toolChoice;

    /** 单次工具调用超时（毫秒） */
    private final long toolTimeoutMs;

    /** 总执行超时（毫秒），0=不超时 */
    private final long totalTimeoutMs;

    private AgentExecutionConfig(Builder builder) {
        this.maxIterations = builder.maxIterations;
        this.temperature = builder.temperature;
        this.maxTokens = builder.maxTokens;
        this.toolChoice = builder.toolChoice;
        this.toolTimeoutMs = builder.toolTimeoutMs;
        this.totalTimeoutMs = builder.totalTimeoutMs;
    }

    public int getMaxIterations() { return maxIterations; }
    public double getTemperature() { return temperature; }
    public int getMaxTokens() { return maxTokens; }
    public ToolChoicePolicy getToolChoice() { return toolChoice; }
    public long getToolTimeoutMs() { return toolTimeoutMs; }
    public long getTotalTimeoutMs() { return totalTimeoutMs; }

    public static AgentExecutionConfig defaults() {
        return new Builder().build();
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private int maxIterations = 10;
        private double temperature = 0.7;
        private int maxTokens = 4096;
        private ToolChoicePolicy toolChoice = ToolChoicePolicy.AUTO;
        private long toolTimeoutMs = 30000;
        private long totalTimeoutMs = 300000; // 5分钟

        public Builder maxIterations(int maxIterations) { this.maxIterations = maxIterations; return this; }
        public Builder temperature(double temperature) { this.temperature = temperature; return this; }
        public Builder maxTokens(int maxTokens) { this.maxTokens = maxTokens; return this; }
        public Builder toolChoice(ToolChoicePolicy toolChoice) { this.toolChoice = toolChoice; return this; }
        public Builder toolTimeoutMs(long toolTimeoutMs) { this.toolTimeoutMs = toolTimeoutMs; return this; }
        public Builder totalTimeoutMs(long totalTimeoutMs) { this.totalTimeoutMs = totalTimeoutMs; return this; }

        public AgentExecutionConfig build() { return new AgentExecutionConfig(this); }
    }
}
