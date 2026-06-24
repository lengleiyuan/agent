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

    private final ModelContextWindow contextWindow;
    private final double safetyMargin;

    public ContextWindowManager(ModelContextWindow contextWindow, double safetyMargin) {
        this.contextWindow = contextWindow;
        this.safetyMargin = safetyMargin;
    }

    public ContextWindowManager(ModelContextWindow contextWindow) {
        this(contextWindow, 0.85);
    }

    public int estimateTokens(List<Msg> messages) {
        return contextWindow.estimateTokens(messages);
    }

    /** 是否超出上下文窗口 */
    public boolean isExceeded(List<Msg> messages) {
        return estimateTokens(messages) > contextWindow.getMaxInputTokens();
    }

    /** 是否接近窗口上限 */
    public boolean isNearLimit(List<Msg> messages) {
        return !contextWindow.isWithinLimit(messages, safetyMargin);
    }

    /**
     * 裁剪消息以适应上下文窗口。
     * 策略：保留系统消息 + 最近 keepLastN 条，确保不超限。
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
