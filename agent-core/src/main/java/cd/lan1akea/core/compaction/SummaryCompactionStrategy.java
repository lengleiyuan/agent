package cd.lan1akea.core.compaction;

import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.MsgRole;
import cd.lan1akea.core.message.SystemMessage;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * 摘要式压缩策略 —— 用 LLM 将早期对话蒸馏为结构化摘要。
 *
 * 范式：
 * 结构：
 *   目标：用户想达成什么
 *   约束：已知限制条件
 *   进度：已完成的关键步骤
 *   关键决策：已做出的重要决定
 *   下一步：接下来要做什么
 *   重要上下文：不能丢失的关键信息
 */
public class SummaryCompactionStrategy implements CompactionStrategy {

    /**
     * 默认压缩触发阈值（75%）。
     */
    private static final double DEFAULT_THRESHOLD = 0.75;

    /**
     * 策略名称。
     */
    private final String name;
    /**
     * 触发压缩的 Token 使用率阈值。
     */
    private final double threshold;

    /**
     * 使用默认名称和阈值创建 SummaryCompactionStrategy。
     */
    public SummaryCompactionStrategy() { this("SummaryCompaction", DEFAULT_THRESHOLD); }

    /**
     * 创建一个 SummaryCompactionStrategy。
     *
     * @param name      策略名称
     * @param threshold 触发压缩的 Token 使用率阈值
     */
    public SummaryCompactionStrategy(String name, double threshold) {
        this.name = name;
        this.threshold = threshold;
    }

    /**
     * 返回策略名称。
     */
    @Override public String getName() { return name; }

    /**
     * 当 Token 使用率达到阈值且消息数量大于 4 时返回 true。
     */
    @Override
    public boolean shouldCompact(List<Msg> messages, int estimatedTokens, int maxInputTokens) {
        if (messages.size() <= 4) return false;
        return (double) estimatedTokens / maxInputTokens >= threshold;
    }

    /**
     * 用 LLM 将早期对话蒸馏为结构化摘要，保留最近 N 条消息。
     */
    @Override
    public Mono<List<Msg>> compact(List<Msg> messages, CompactionContext ctx) {
        int keepRecent = ctx.getKeepRecent();
        int removeCount = messages.size() - keepRecent;
        if (removeCount <= 0) return Mono.just(messages);

        List<Msg> oldMsgs = new ArrayList<>(messages.subList(0, removeCount));
        List<Msg> recentMsgs = new ArrayList<>(messages.subList(removeCount, messages.size()));

        // 构建结构化摘要提示词
        String summaryPrompt = buildSummaryPrompt(oldMsgs);

        // 用模型做蒸馏（如果模型可用），否则走简单拼接
        if (ctx.getModel() != null) {
            cd.lan1akea.core.model.GenerateOptions opts = ctx.getGenerateOptions() != null
                ? ctx.getGenerateOptions()
                : cd.lan1akea.core.model.GenerateOptions.builder().maxTokens(ctx.getModel().getDefaultMaxTokens()).build();
            return ctx.getModel().chat(
                    List.of(SystemMessage.of(summaryPrompt)), opts)
                .map(resp -> {
                    List<Msg> result = new ArrayList<>(recentMsgs.size() + 1);
                    result.add(SystemMessage.of("对话摘要:\n" + resp.getMessage().getTextContent()));
                    result.addAll(recentMsgs);
                    return result;
                })
                .switchIfEmpty(Mono.fromCallable(() -> fallbackConcat(oldMsgs, recentMsgs)));
        }

        return Mono.fromCallable(() -> fallbackConcat(oldMsgs, recentMsgs));
    }

    /**
     * 构建结构化摘要提示词，要求 LLM 按固定格式输出。
     */
    private String buildSummaryPrompt(List<Msg> oldMsgs) {
        StringBuilder sb = new StringBuilder();
        sb.append("请将以下对话历史蒸馏为结构化摘要，用中文输出：\n\n");
        sb.append("## 目标\n用户想达成什么\n\n");
        sb.append("## 约束\n已知限制条件\n\n");
        sb.append("## 进度\n已完成的关键步骤\n\n");
        sb.append("## 关键决策\n已做出的重要决定\n\n");
        sb.append("## 下一步\n接下来要做什么\n\n");
        sb.append("## 重要上下文\n不能丢失的关键信息\n\n");
        sb.append("---\n对话历史:\n");
        for (Msg msg : oldMsgs) {
            sb.append("[").append(msg.getRole().name()).append("] ")
                .append(truncate(msg.getTextContent(), 500)).append("\n");
        }
        return sb.toString();
    }

    /**
     * 模型不可用时的降级处理：简单拼接摘要和最近消息。
     */
    private List<Msg> fallbackConcat(List<Msg> oldMsgs, List<Msg> recentMsgs) {
        StringBuilder sb = new StringBuilder("对话摘要:\n");
        for (int i = 0; i < Math.min(oldMsgs.size(), 10); i++) {
            sb.append("- ").append(truncate(oldMsgs.get(i).getTextContent(), 200)).append("\n");
        }
        List<Msg> result = new ArrayList<>(recentMsgs.size() + 1);
        result.add(SystemMessage.of(sb.toString()));
        result.addAll(recentMsgs);
        return result;
    }

    /**
     * 截断字符串到指定长度，超出部分以省略号替代。
     */
    private static String truncate(String s, int max) {
        if (s == null || s.isEmpty()) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
