package cd.lan1akea.core.model;

/**
 * 模型上下文窗口配置。
 * <p>
 * 记录各模型的上下文窗口大小和输出上限。
 * </p>
 */
public class ModelContextWindow {

    /** 模型名称 */
    private final String modelName;

    /** 最大输入 Token 数 */
    private final int maxInputTokens;

    /** 最大输出 Token 数 */
    private final int maxOutputTokens;

    public ModelContextWindow(String modelName, int maxInputTokens, int maxOutputTokens) {
        this.modelName = modelName;
        this.maxInputTokens = maxInputTokens;
        this.maxOutputTokens = maxOutputTokens;
    }

    /** @return 模型名称 */
    public String getModelName() { return modelName; }

    /** @return 最大输入Token */
    public int getMaxInputTokens() { return maxInputTokens; }

    /** @return 最大输出Token */
    public int getMaxOutputTokens() { return maxOutputTokens; }

    /** @return 上下文窗口总大小 */
    public int getTotalWindow() { return maxInputTokens + maxOutputTokens; }
}
