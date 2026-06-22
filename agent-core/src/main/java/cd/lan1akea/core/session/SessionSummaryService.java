package cd.lan1akea.core.session;

import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.MsgRole;

import java.util.List;

/**
 * 会话摘要服务。
 * <p>
 * 当对话历史超出模型上下文窗口时，对早期轮次进行摘要压缩。
 * </p>
 */
public class SessionSummaryService {

    /** 触发摘要的 Token 阈值 */
    private static final int SUMMARY_THRESHOLD_TOKENS = 3000;

    /**
     * 判断是否需要摘要。
     *
     * @param estimatedTokens 估计 Token 数
     * @return true 如果超过阈值
     */
    public boolean shouldSummarize(int estimatedTokens) {
        return estimatedTokens > SUMMARY_THRESHOLD_TOKENS;
    }

    /**
     * 生成会话摘要消息。
     * <p>
     * 默认实现拼接早期轮次文本，生成简短的摘要提示。
     * 子类可覆写，调用 LLM 生成更精确的摘要。
     * </p>
     *
     * @param turns 待摘要的对话轮次
     * @return 摘要系统消息
     */
    public Msg summarize(List<ChatTurn> turns) {
        StringBuilder summary = new StringBuilder();
        summary.append("之前的对话摘要:\n");

        int count = 0;
        for (ChatTurn turn : turns) {
            summary.append("轮次 ").append(turn.getTurnOrder())
                .append(": ").append(truncate(turn.getUserMsgJson(), 200))
                .append("\n");
            count++;
        }
        summary.append("\n(以上是前 ").append(count).append(" 轮对话的摘要，请基于这些上下文继续对话)");

        return Msg.builder(MsgRole.SYSTEM)
            .addText(summary.toString())
            .build();
    }

    private String truncate(String str, int maxLen) {
        if (str == null) return "";
        return str.length() <= maxLen ? str : str.substring(0, maxLen) + "...";
    }
}
