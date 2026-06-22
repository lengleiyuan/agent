package cd.lan1akea.core.model;

import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.SystemMessage;
import cd.lan1akea.core.message.UserMessage;

/**
 * 模型工具方法。
 */
public final class ModelUtils {

    private ModelUtils() {
        // 工具类
    }

    /**
     * 估算消息列表的近似 Token 数量。
     * <p>
     * 粗略估算：英文约 char/4，中文约 char/1.5，取平均 char/2。
     * </p>
     *
     * @param messages 消息列表
     * @return 近似 Token 数
     */
    public static int estimateTokens(java.util.List<Msg> messages) {
        int totalChars = 0;
        for (Msg msg : messages) {
            if (msg instanceof SystemMessage || msg instanceof UserMessage) {
                totalChars += msg.getTextContent().length();
            }
        }
        return totalChars / 2;
    }

    /**
     * 判断是否接近上下文窗口上限。
     *
     * @param messageTokens 消息 Token 数
     * @param maxTokens     上下文窗口大小
     * @param safetyMargin  安全余量比例（如 0.8 表示使用率80%时触发）
     * @return true 如果接近上限
     */
    public static boolean isNearContextLimit(int messageTokens, int maxTokens, double safetyMargin) {
        return (double) messageTokens / maxTokens >= safetyMargin;
    }
}
