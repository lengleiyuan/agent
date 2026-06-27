package cd.lan1akea.core.model;

import cd.lan1akea.core.message.Msg;

import java.util.List;

/**
 * 模型上下文窗口配置。
 */
public class ModelContextWindow {

    /**
     * 模型名称
     */
    private final String modelName;
    /**
     * 最大输入 Token 数
     */
    private final int maxInputTokens;
    /**
     * 最大输出 Token 数
     */
    private final int maxOutputTokens;
    /**
     * Token 估算器
     */
    private final TokenEstimator estimator;

    /**
     * 创建上下文窗口（使用默认 Token 估算器）。
     *
     * @param modelName       模型名称
     * @param maxInputTokens  最大输入 Token 数
     * @param maxOutputTokens 最大输出 Token 数
     */
    public ModelContextWindow(String modelName, int maxInputTokens, int maxOutputTokens) {
        this(modelName, maxInputTokens, maxOutputTokens, TokenEstimator.defaults());
    }

    /**
     * 创建上下文窗口（使用自定义 Token 估算器）。
     *
     * @param modelName       模型名称
     * @param maxInputTokens  最大输入 Token 数
     * @param maxOutputTokens 最大输出 Token 数
     * @param estimator       Token 估算器
     */
    public ModelContextWindow(String modelName, int maxInputTokens, int maxOutputTokens,
                              TokenEstimator estimator) {
        this.modelName = modelName;
        this.maxInputTokens = maxInputTokens;
        this.maxOutputTokens = maxOutputTokens;
        this.estimator = estimator;
    }

    /**
     * @return 模型名称
     */
    public String getModelName() { return modelName; }
    /**
     * @return 最大输入 Token 数
     */
    public int getMaxInputTokens() { return maxInputTokens; }
    /**
     * @return 最大输出 Token 数
     */
    public int getMaxOutputTokens() { return maxOutputTokens; }
    /**
     * @return 总窗口大小（输入+输出）
     */
    public int getTotalWindow() { return maxInputTokens + maxOutputTokens; }
    /**
     * @return Token 估算器
     */
    public TokenEstimator getEstimator() { return estimator; }

    /**
     * 估算消息的 Token 数。
     *
     * @param messages 消息列表
     * @return 估算 Token 数
     */
    public int estimateTokens(List<Msg> messages) {
        return estimator.estimate(messages);
    }

    /**
     * 检查消息是否在上下文窗口限制内。
     *
     * @param messages     消息列表
     * @param safetyMargin 安全裕度比率（如 0.9 表示 90%）
     * @return 在限制内返回 true
     */
    public boolean isWithinLimit(List<Msg> messages, double safetyMargin) {
        return (double) estimator.estimate(messages) / maxInputTokens < safetyMargin;
    }
}
