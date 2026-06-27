package cd.lan1akea.core.model;

import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.MsgRole;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文窗口管理器。
 * 负责 Token 用量计算、超限判断、消息裁剪。
 */
public class ContextWindowManager {

    /**
     * 模型上下文窗口配置
     */
    private final ModelContextWindow contextWindow;
    /**
     * 接近限制检测的安全裕度比率
     */
    private final double safetyMargin;

    /**
     * 创建上下文窗口管理器。
     *
     * @param contextWindow 模型上下文窗口配置
     * @param safetyMargin  安全裕度比率（如 0.85 表示 85%）
     */
    public ContextWindowManager(ModelContextWindow contextWindow, double safetyMargin) {
        this.contextWindow = contextWindow;
        this.safetyMargin = safetyMargin;
    }

    /**
     * 创建上下文窗口管理器（默认安全裕度 0.85）。
     *
     * @param contextWindow 模型上下文窗口配置
     */
    public ContextWindowManager(ModelContextWindow contextWindow) {
        this(contextWindow, 0.85);
    }

    /**
     * 估算消息的 Token 数。
     *
     * @param messages 消息列表
     * @return 估算 Token 数
     */
    public int estimateTokens(List<Msg> messages) {
        return contextWindow.estimateTokens(messages);
    }

    /**
     * 检查消息是否超过上下文窗口限制。
     *
     * @param messages 消息列表
     * @return 超出返回 true
     */
    public boolean isExceeded(List<Msg> messages) {
        return estimateTokens(messages) > contextWindow.getMaxInputTokens();
    }

    /**
     * 检查消息是否接近上下文窗口限制。
     *
     * @param messages 消息列表
     * @return 接近限制返回 true
     */
    public boolean isNearLimit(List<Msg> messages) {
        return !contextWindow.isWithinLimit(messages, safetyMargin);
    }

    /**
     * 裁剪消息使其适应上下文窗口。
     * 策略：保留系统消息和最近 keepLastN 条非系统消息。
     *
     * @param messages  完整消息列表
     * @param keepLastN 保留的最近非系统消息数
     * @return 裁剪后的消息列表
     */
    public List<Msg> trim(List<Msg> messages, int keepLastN) {
        int tokens = estimateTokens(messages);
        if (tokens <= contextWindow.getMaxInputTokens()) {
            return new ArrayList<>(messages);
        }

        List<Msg> systemMsgs = new ArrayList<>();
        List<Msg> others = new ArrayList<>();
        for (Msg msg : messages) {
            if (msg.getRole() == MsgRole.SYSTEM) {
                systemMsgs.add(msg);
            } else {
                others.add(msg);
            }
        }

        int n = keepLastN;
        List<Msg> result;
        do {
            result = new ArrayList<>(systemMsgs);
            int start = Math.max(0, others.size() - n);
            result.addAll(others.subList(start, others.size()));
            n--;
        } while (estimateTokens(result) > contextWindow.getMaxInputTokens() && n > 1);

        return result;
    }
}
