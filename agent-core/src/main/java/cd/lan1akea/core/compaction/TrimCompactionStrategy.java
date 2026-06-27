package cd.lan1akea.core.compaction;

import cd.lan1akea.core.message.Msg;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * 裁剪式压缩策略 —— 直接丢弃早期消息，保留最近 N 条。
 * 最简单、最快速的压缩方式，不需要 LLM 调用。
 */
public class TrimCompactionStrategy implements CompactionStrategy {

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
     * 使用默认名称和阈值创建 TrimCompactionStrategy。
     */
    public TrimCompactionStrategy() { this("TrimCompaction", DEFAULT_THRESHOLD); }

    /**
     * 创建一个 TrimCompactionStrategy。
     *
     * @param name      策略名称
     * @param threshold 触发压缩的 Token 使用率阈值
     */
    public TrimCompactionStrategy(String name, double threshold) {
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
     * 直接丢弃早期消息，只保留最近 N 条。
     */
    @Override
    public Mono<List<Msg>> compact(List<Msg> messages, CompactionContext ctx) {
        int keepRecent = ctx.getKeepRecent();
        if (messages.size() <= keepRecent) return Mono.just(messages);
        return Mono.just(new ArrayList<>(messages.subList(messages.size() - keepRecent, messages.size())));
    }
}
