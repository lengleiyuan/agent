package cd.lan1akea.core.hook.impl;

import cd.lan1akea.core.CoreConstants.EventPayload;
import cd.lan1akea.core.CoreConstants.HookSource;
import cd.lan1akea.core.agent.loop.LoopContext;
import cd.lan1akea.core.exception.HookAbortException;
import cd.lan1akea.core.hook.AroundHook;
import cd.lan1akea.core.hook.Hook;
import cd.lan1akea.core.hook.HookContext;
import cd.lan1akea.core.hook.HookEvent;
import cd.lan1akea.core.hook.HookEventType;
import cd.lan1akea.core.hook.HookResult;
import cd.lan1akea.core.metrics.AgentMetrics;
import cd.lan1akea.core.model.ChatStreamChunk;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.function.Function;

/**
 * Agent 指标收集 Hook。
 *
 * <p>同时实现 {@link Hook} 和 {@link AroundHook}：
 * <ul>
 *   <li>AroundHook — 包裹模型调用，记录 LLM 延迟</li>
 *   <li>Hook — 订阅 AFTER_ITERATION，记录迭代次数</li>
 * </ul>
 *
 * <p>替代原先散落在 ModelCallPipeline 和 LoopExecutor 中的硬编码 metrics 调用。
 */
public class AgentMetricsHook implements Hook, AroundHook {

    /** Hook 名称 */
    private final String name;
    /** 指标收集器 */
    private final AgentMetrics metrics;
    /** 模型名称 */
    private final String modelName;
    /** 模型提供商 */
    private final String provider;

    /**
     * 创建 Agent 指标 Hook。
     *
     * @param name      Hook 名称
     * @param metrics   指标收集器
     * @param modelName 模型名称
     * @param provider  模型提供商
     */
    public AgentMetricsHook(String name, AgentMetrics metrics,
                             String modelName, String provider) {
        this.name = name;
        this.metrics = metrics;
        this.modelName = modelName;
        this.provider = provider;
    }

    // ============================================================
    // Hook — 迭代指标
    // ============================================================

    /**
     * @return Hook 名称
     */
    @Override
    public String getName() { return name; }

    /**
     * @return AFTER_ITERATION 事件
     */
    @Override
    public Set<HookEventType> getSubscribedEventTypes() {
        return Set.of(HookEventType.AFTER_ITERATION);
    }

    /**
     * 低优先级，在所有业务 Hook 之后执行。
     */
    @Override
    public int getPriority() { return 900; }

    /**
     * 记录迭代次数指标。
     */
    @Override
    public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
        LoopContext lc = event.getPayload(EventPayload.LOOP_CONTEXT);
        if (lc != null) {
            metrics.recordIteration(lc.getAgentName(), lc.getSessionId(),
                    lc.getIteration(), 0);
        }
        return Mono.just(HookResult.continue_());
    }

    // ============================================================
    // AroundHook — LLM 延迟指标
    // ============================================================

    /**
     * 包裹模型调用，记录延迟指标。
     *
     * @param event 推理事件
     * @param ctx   Hook 上下文
     * @param next  下游模型调用
     * @return 包裹后的流式分块
     */
    @Override
    public Flux<ChatStreamChunk> aroundReasoningStream(HookEvent event, HookContext ctx,
                                                        Function<HookEvent, Flux<ChatStreamChunk>> next) {
        final long start = System.currentTimeMillis();
        return next.apply(event)
                .doOnNext(chunk -> {
                    if (chunk.getFinishReason() != null) {
                        long latency = System.currentTimeMillis() - start;
                        metrics.recordLlmCall(modelName, provider, latency, 0, 0, true, null);
                    }
                });
    }
}
