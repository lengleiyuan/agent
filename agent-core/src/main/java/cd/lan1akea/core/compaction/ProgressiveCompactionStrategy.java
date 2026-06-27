package cd.lan1akea.core.compaction;

import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.model.ModelContextWindow;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;

/**
 * 渐进式压缩策略 —— 按优先级链式尝试，便宜的先上。
 *
 * 参考 Claude Code 的四层渐进体系：
 * Snip（免费） → Trim（免费） → Summary（付费 LLM）
 *
 * 每一级压缩后重新估算 Token，不达标则继续下一级。
 */
public class ProgressiveCompactionStrategy implements CompactionStrategy {

    /**
     * 策略名称。
     */
    private final String name;
    /**
     * 子策略列表，按优先级顺序执行。
     */
    private final List<CompactionStrategy> strategies;
    /**
     * 用于估算 Token 数的上下文窗口。
     */
    private final ModelContextWindow contextWindow;

    /**
     * 使用默认名称创建 ProgressiveCompactionStrategy。
     *
     * @param contextWindow Token 估算器
     * @param strategies    按优先级排列的子策略
     */
    public ProgressiveCompactionStrategy(ModelContextWindow contextWindow,
                                          CompactionStrategy... strategies) {
        this("ProgressiveCompaction", contextWindow, Arrays.asList(strategies));
    }

    /**
     * 创建一个 ProgressiveCompactionStrategy。
     *
     * @param name          策略名称
     * @param contextWindow Token 估算器
     * @param strategies    按优先级排列的子策略列表
     */
    public ProgressiveCompactionStrategy(String name, ModelContextWindow contextWindow,
                                          List<CompactionStrategy> strategies) {
        this.name = name;
        this.contextWindow = contextWindow;
        this.strategies = strategies;
    }

    /**
     * 返回策略名称。
     */
    @Override public String getName() { return name; }

    /**
     * 如果任意子策略需要压缩则返回 true。
     */
    @Override
    public boolean shouldCompact(List<Msg> messages, int estimatedTokens, int maxInputTokens) {
        for (CompactionStrategy s : strategies) {
            if (s.shouldCompact(messages, estimatedTokens, maxInputTokens)) return true;
        }
        return false;
    }

    /**
     * 按优先级链式执行子策略，每级压缩后重新估算 Token，不达标则继续下一级。
     */
    @Override
    public Mono<List<Msg>> compact(List<Msg> messages, CompactionContext ctx) {
        return Mono.defer(() -> {
            Mono<List<Msg>> chain = Mono.just(messages);
            int maxTokens = ctx.getMaxInputTokens();

            for (CompactionStrategy strategy : strategies) {
                chain = chain.flatMap(msgs -> {
                    int estimated = contextWindow.estimateTokens(msgs);
                    if (!strategy.shouldCompact(msgs, estimated, maxTokens)) {
                        return Mono.just(msgs);
                    }
                    return strategy.compact(msgs, ctx)
                        .map(compressed -> {
                            int after = contextWindow.estimateTokens(compressed);
                            if (after < estimated) {
                                // 有效压缩，继续下一级
                            }
                            return compressed;
                        });
                });
            }
            return chain;
        });
    }
}
