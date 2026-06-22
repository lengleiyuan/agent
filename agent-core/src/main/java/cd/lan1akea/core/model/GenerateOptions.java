package cd.lan1akea.core.model;

import java.util.List;

/**
 * 生成选项。
 * <p>
 * 控制 LLM 生成行为的参数集合。
 * </p>
 */
public class GenerateOptions {

    /** 温度（0.0 ~ 2.0），控制随机性 */
    private final Double temperature;

    /** Top-P 采样（0.0 ~ 1.0） */
    private final Double topP;

    /** 最大输出 Token 数 */
    private final Integer maxTokens;

    /** 停止词列表 */
    private final List<String> stopSequences;

    /** 是否启用流式输出 */
    private final boolean stream;

    /** 工具选择策略 */
    private final ToolChoicePolicy toolChoice;

    private GenerateOptions(Builder builder) {
        this.temperature = builder.temperature;
        this.topP = builder.topP;
        this.maxTokens = builder.maxTokens;
        this.stopSequences = builder.stopSequences;
        this.stream = builder.stream;
        this.toolChoice = builder.toolChoice;
    }

    /** @return 温度 */
    public Double getTemperature() { return temperature; }

    /** @return Top-P */
    public Double getTopP() { return topP; }

    /** @return 最大Token数 */
    public Integer getMaxTokens() { return maxTokens; }

    /** @return 停止词 */
    public List<String> getStopSequences() { return stopSequences; }

    /** @return 是否流式 */
    public boolean isStream() { return stream; }

    /** @return 工具选择策略 */
    public ToolChoicePolicy getToolChoice() { return toolChoice; }

    /** 创建默认选项 */
    public static GenerateOptions defaults() {
        return new Builder().build();
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Double temperature;
        private Double topP;
        private Integer maxTokens;
        private List<String> stopSequences;
        private boolean stream;
        private ToolChoicePolicy toolChoice = ToolChoicePolicy.AUTO;

        public Builder temperature(Double temperature) { this.temperature = temperature; return this; }
        public Builder topP(Double topP) { this.topP = topP; return this; }
        public Builder maxTokens(Integer maxTokens) { this.maxTokens = maxTokens; return this; }
        public Builder stopSequences(List<String> stopSequences) { this.stopSequences = stopSequences; return this; }
        public Builder stream(boolean stream) { this.stream = stream; return this; }
        public Builder toolChoice(ToolChoicePolicy toolChoice) { this.toolChoice = toolChoice; return this; }

        public GenerateOptions build() { return new GenerateOptions(this); }
    }
}
