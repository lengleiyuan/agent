package cd.lan1akea.core.agent.config;

import cd.lan1akea.core.model.ToolChoicePolicy;

/**
 * Agent 执行配置。
 * 控制 ReAct 循环的行为参数。
 */
public class AgentExecutionConfig {

    /**
     * 最大 ReAct 迭代次数。
     */
    private final int maxIterations;
    /**
     * LLM 生成温度。
     */
    private final double temperature;
    /**
     * 最大输出 token 数。
     */
    private final int maxTokens;
    /**
     * 工具选择策略。
     */
    private final ToolChoicePolicy toolChoice;
    /**
     * 单次工具调用超时（毫秒）。
     */
    private final long toolTimeoutMs;
    /**
     * 总执行超时（毫秒），0 表示无超时。
     */
    private final long totalTimeoutMs;
    /**
     * 迭代间退避延迟（毫秒），0 表示无退避。
     */
    private final long iterationBackoffMs;

    /**
     * 通过 Builder 构造 AgentExecutionConfig。
     *
     * @param builder 配置了各字段的 Builder
     */
    private AgentExecutionConfig(Builder builder) {
        this.maxIterations = builder.maxIterations;
        this.temperature = builder.temperature;
        this.maxTokens = builder.maxTokens;
        this.toolChoice = builder.toolChoice;
        this.toolTimeoutMs = builder.toolTimeoutMs;
        this.totalTimeoutMs = builder.totalTimeoutMs;
        this.iterationBackoffMs = builder.iterationBackoffMs;
    }

    /**
     * @return 最大 ReAct 迭代次数
     */
    public int getMaxIterations() { return maxIterations; }
    /**
     * @return LLM 温度
     */
    public double getTemperature() { return temperature; }
    /**
     * @return 最大输出 token 数
     */
    public int getMaxTokens() { return maxTokens; }
    /**
     * @return 工具选择策略
     */
    public ToolChoicePolicy getToolChoice() { return toolChoice; }
    /**
     * @return 工具超时毫秒数
     */
    public long getToolTimeoutMs() { return toolTimeoutMs; }
    /**
     * @return 总超时毫秒数
     */
    public long getTotalTimeoutMs() { return totalTimeoutMs; }
    /**
     * @return 迭代间退避毫秒数
     */
    public long getIterationBackoffMs() { return iterationBackoffMs; }

    /**
     * 返回默认执行配置。
     *
     * @return 默认配置
     */
    public static AgentExecutionConfig defaults() {
        return new Builder().build();
    }

    /**
     * 创建 Builder。
     *
     * @return 新的 Builder
     */
    public static Builder builder() { return new Builder(); }

    /**
     * AgentExecutionConfig 建造者。
     */
    public static class Builder {
        /** 最大迭代次数，默认 10 */
        private int maxIterations = 10;
        /** LLM 温度，默认 0.7 */
        private double temperature = 0.7;
        /** 最大输出 token 数，默认 4096 */
        private int maxTokens = 4096;
        /** 工具选择策略，默认 AUTO */
        private ToolChoicePolicy toolChoice = ToolChoicePolicy.AUTO;
        /** 工具超时毫秒数，默认 30000 */
        private long toolTimeoutMs = 30000;
        /** 总超时毫秒数，默认 300000 */
        private long totalTimeoutMs = 300000;
        /** 迭代间退避毫秒数，默认 0（无退避） */
        private long iterationBackoffMs = 0;

        /**
         * 设置最大迭代次数。
         */
        public Builder maxIterations(int maxIterations) { this.maxIterations = maxIterations; return this; }
        /**
         * 设置温度。
         */
        public Builder temperature(double temperature) { this.temperature = temperature; return this; }
        /**
         * 设置最大输出 token 数。
         */
        public Builder maxTokens(int maxTokens) { this.maxTokens = maxTokens; return this; }
        /**
         * 设置工具选择策略。
         */
        public Builder toolChoice(ToolChoicePolicy toolChoice) { this.toolChoice = toolChoice; return this; }
        /**
         * 设置工具超时（毫秒）。
         */
        public Builder toolTimeoutMs(long toolTimeoutMs) { this.toolTimeoutMs = toolTimeoutMs; return this; }
        /**
         * 设置总超时（毫秒）。
         */
        public Builder totalTimeoutMs(long totalTimeoutMs) { this.totalTimeoutMs = totalTimeoutMs; return this; }
        /**
         * 设置迭代间退避延迟（毫秒），0 表示无退避。
         */
        public Builder iterationBackoffMs(long iterationBackoffMs) { this.iterationBackoffMs = iterationBackoffMs; return this; }

        /**
         * 构建 AgentExecutionConfig。
         *
         * @return 新的 AgentExecutionConfig
         */
        public AgentExecutionConfig build() { return new AgentExecutionConfig(this); }
    }
}
