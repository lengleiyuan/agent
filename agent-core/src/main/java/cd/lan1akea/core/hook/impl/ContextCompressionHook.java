package cd.lan1akea.core.hook.impl;

import cd.lan1akea.core.compaction.CompactionContext;
import cd.lan1akea.core.compaction.CompactionStrategy;
import cd.lan1akea.core.compaction.SummaryCompactionStrategy;
import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.model.ModelContextWindow;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;

/**
 * 上下文压缩 Hook（PreReasoningHook）。
 *
 * 通过 CompactionStrategy 实现可插拔的压缩范式。
 * 默认使用 SummaryCompactionStrategy（LLM 结构化摘要）。
 */
public class ContextCompressionHook implements Hook {

    /**
     * Hook 名称
     */
    private final String name;
    /**
     * 压缩策略
     */
    private final CompactionStrategy strategy;
    /**
     * 模型上下文窗口
     */
    private final ModelContextWindow contextWindow;
    /**
     * 压缩上下文
     */
    private final CompactionContext compactionContext;

    /**
     * 创建上下文压缩 Hook（默认名称）。
     */
    public ContextCompressionHook(CompactionStrategy strategy, ModelContextWindow contextWindow,
                                   CompactionContext compactionContext) {
        this("ContextCompression", strategy, contextWindow, compactionContext);
    }

    /**
     * 创建上下文压缩 Hook。
     */
    public ContextCompressionHook(String name, CompactionStrategy strategy,
                                   ModelContextWindow contextWindow,
                                   CompactionContext compactionContext) {
        this.name = name;
        this.strategy = strategy != null ? strategy : new SummaryCompactionStrategy();
        this.contextWindow = contextWindow;
        this.compactionContext = compactionContext;
    }

    /**
     * @return Hook 名称
     */
    @Override public String getName() { return name; }

    /**
     * @return PRE_REASONING 事件类型
     */
    @Override public Set<HookEventType> getSubscribedEventTypes() {
        return Set.of(HookEventType.PRE_REASONING);
    }

    /**
     * 最高优先级（5），在内存检索之前执行。
     */
    @Override public int getPriority() { return 5; }

    /**
     * 检查上下文窗口是否需要压缩，如需要则执行压缩。
     */
    @Override
    public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
        if (event.getMessages() == null) return Mono.just(HookResult.continue_());
        List<Msg> messages = event.getMessages();

        int estimatedTokens = contextWindow.estimateTokens(messages);

        if (!strategy.shouldCompact(messages, estimatedTokens, contextWindow.getMaxInputTokens())) {
            return Mono.just(HookResult.continue_());
        }

        return strategy.compact(messages, compactionContext)
            .map(compressed -> {
                event.setMessages(compressed);
                return HookResult.modify("压缩完成: " + messages.size()
                    + " → " + compressed.size() + " 条消息");
            });
    }
}
