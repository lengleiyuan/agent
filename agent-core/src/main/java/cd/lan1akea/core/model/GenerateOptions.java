package cd.lan1akea.core.model;

import java.util.List;

/**
 * 生成选项。
 * 控制 LLM 生成行为的参数集合。
 */
public class GenerateOptions {

    /**
     * 温度（0.0 ~ 2.0），控制随机性
     */
    private final Double temperature;

    /**
     * Top-P 采样（0.0 ~ 1.0）
     */
    private final Double topP;

    /**
     * 最大输出 Token 数
     */
    private final Integer maxTokens;

    /**
     * 停止词列表
     */
    private final List<String> stopSequences;

    /**
     * 是否启用流式输出
     */
    private final boolean stream;

    /**
     * 工具选择策略
     */
    private final ToolChoicePolicy toolChoice;

    private GenerateOptions(Builder builder) {
        this.temperature = builder.temperature;
        this.topP = builder.topP;
        this.maxTokens = builder.maxTokens;
        this.stopSequences = builder.stopSequences;
        this.stream = builder.stream;
        this.toolChoice = builder.toolChoice;
    }

    /**
     * @return 温度
     */
    public Double getTemperature() { return temperature; }

    /**
     * @return Top-P
     */
    public Double getTopP() { return topP; }

    /**
     * @return 最大Token数
     */
    public Integer getMaxTokens() { return maxTokens; }

    /**
     * @return 停止词
     */
    public List<String> getStopSequences() { return stopSequences; }

    /**
     * @return 是否流式
     */
    public boolean isStream() { return stream; }

    /**
     * @return 工具选择策略
     */
    public ToolChoicePolicy getToolChoice() { return toolChoice; }

    /**
     * 创建默认生成选项。
     *
     * @return 默认选项
     */
    public static GenerateOptions defaults() {
        return new Builder().build();
    }

    /**
     * 创建 GenerateOptions 建造者。
     *
     * @return 新建造者
     */
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Double temperature;
        private Double topP;
        private Integer maxTokens;
        private List<String> stopSequences;
        private boolean stream;
        private ToolChoicePolicy toolChoice = ToolChoicePolicy.AUTO;

        /**
         * 设置温度
         */
        public Builder temperature(Double temperature) { this.temperature = temperature; return this; }
        /**
         * 设置 Top-P 采样
         */
        public Builder topP(Double topP) { this.topP = topP; return this; }
        /**
         * 设置最大输出 Token 数
         */
        public Builder maxTokens(Integer maxTokens) { this.maxTokens = maxTokens; return this; }
        /**
         * 设置停止词列表
         */
        public Builder stopSequences(List<String> stopSequences) { this.stopSequences = stopSequences; return this; }
        /**
         * 启用流式输出
         */
        public Builder stream(boolean stream) { this.stream = stream; return this; }
        /**
         * 设置工具选择策略
         */
        public Builder toolChoice(ToolChoicePolicy toolChoice) { this.toolChoice = toolChoice; return this; }

        /**
         * 构建 GenerateOptions。
         *
         * @return 新的 GenerateOptions
         */
        public GenerateOptions build() { return new GenerateOptions(this); }
    }
}
