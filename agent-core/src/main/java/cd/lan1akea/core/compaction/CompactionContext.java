package cd.lan1akea.core.compaction;

import cd.lan1akea.core.model.ChatModel;
import cd.lan1akea.core.model.GenerateOptions;

/**
 * 压缩上下文 —— 传递给 CompactionStrategy#compact 的辅助信息。
 */
public class CompactionContext {

    /**
     * 用于生成摘要的语言模型。
     */
    private final ChatModel model;
    /**
     * 生成选项。
     */
    private final GenerateOptions generateOptions;
    /**
     * 模型最大输入 Token 数。
     */
    private final int maxInputTokens;
    /**
     * 压缩时应保留的最近消息条数。
     */
    private final int keepRecent;

    /**
     * 创建一个 CompactionContext。
     *
     * @param model             语言模型
     * @param generateOptions   生成选项
     * @param maxInputTokens    最大输入 Token 数
     * @param keepRecent        保留的最近消息条数
     */
    public CompactionContext(ChatModel model, GenerateOptions generateOptions,
                              int maxInputTokens, int keepRecent) {
        this.model = model;
        this.generateOptions = generateOptions;
        this.maxInputTokens = maxInputTokens;
        this.keepRecent = keepRecent;
    }

    /**
     * 返回语言模型。
     */
    public ChatModel getModel() { return model; }
    /**
     * 返回生成选项。
     */
    public GenerateOptions getGenerateOptions() { return generateOptions; }
    /**
     * 返回最大输入 Token 数。
     */
    public int getMaxInputTokens() { return maxInputTokens; }
    /**
     * 返回保留的最近消息条数。
     */
    public int getKeepRecent() { return keepRecent; }

    /**
     * 创建一个新的 Builder。
     */
    public static Builder builder() { return new Builder(); }

    /**
     * CompactionContext 的建造者。
     */
    public static class Builder {
        /**
         * 语言模型。
         */
        private ChatModel model;
        /**
         * 生成选项。
         */
        private GenerateOptions generateOptions;
        /**
         * 最大输入 Token 数，默认 8000。
         */
        private int maxInputTokens = 8000;
        /**
         * 保留的最近消息条数，默认 4。
         */
        private int keepRecent = 4;

        /**
         * 设置语言模型。
         */
        public Builder model(ChatModel v) { this.model = v; return this; }
        /**
         * 设置生成选项。
         */
        public Builder generateOptions(GenerateOptions v) { this.generateOptions = v; return this; }
        /**
         * 设置最大输入 Token 数。
         */
        public Builder maxInputTokens(int v) { this.maxInputTokens = v; return this; }
        /**
         * 设置保留的最近消息条数。
         */
        public Builder keepRecent(int v) { this.keepRecent = v; return this; }
        /**
         * 构建并返回 CompactionContext。
         */
        public CompactionContext build() { return new CompactionContext(model, generateOptions, maxInputTokens, keepRecent); }
    }
}
