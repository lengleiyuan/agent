package cd.lan1akea.core.model;

import cd.lan1akea.core.message.Msg;

import java.util.List;

/**
 * 模型上下文窗口配置。
 */
public class ModelContextWindow {

    private final String modelName;
    private final int maxInputTokens;
    private final int maxOutputTokens;
    private final TokenEstimator estimator;

    public ModelContextWindow(String modelName, int maxInputTokens, int maxOutputTokens) {
        this(modelName, maxInputTokens, maxOutputTokens, TokenEstimator.defaults());
    }

    public ModelContextWindow(String modelName, int maxInputTokens, int maxOutputTokens,
                              TokenEstimator estimator) {
        this.modelName = modelName;
        this.maxInputTokens = maxInputTokens;
        this.maxOutputTokens = maxOutputTokens;
        this.estimator = estimator;
    }

    public String getModelName() { return modelName; }
    public int getMaxInputTokens() { return maxInputTokens; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public int getTotalWindow() { return maxInputTokens + maxOutputTokens; }
    public TokenEstimator getEstimator() { return estimator; }

    /** 估算消息列表的 Token 数 */
    public int estimateTokens(List<Msg> messages) {
        return estimator.estimate(messages);
    }

    /** 判断消息是否在窗口限制内 */
    public boolean isWithinLimit(List<Msg> messages, double safetyMargin) {
        return (double) estimator.estimate(messages) / maxInputTokens < safetyMargin;
    }
}
