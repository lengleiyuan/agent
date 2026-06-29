package cd.lan1akea.core.hook;

import cd.lan1akea.core.model.ChatStreamChunk;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.function.Function;

/**
 * 包裹式（Onion）Hook，前置和后置逻辑在同一个方法作用域内。
 * 链式 Hook 的 PRE 和 POST 是独立调用，需要外部桥接状态。
 * 包裹式用一个方法包裹整个阶段，局部变量天然线程安全。
 * 两种 Hook 可以共存。链式适合无状态拦截（过滤、校验、审计），
 * 包裹式适合有临时共享数据的场景（计时、token 追踪、事务管理）。
 * 注意：AroundHook 消除的是 per-request 跨 PRE/POST 桥接状态的线程安全问题。
 * 全局聚合统计仍需 Atomic 变量，与 Hook 模型无关。
 */
public interface AroundHook {

    /**
     * @return Hook 唯一名称
     */
    String getName();

    /**
     * 包裹推理阶段（非流式）。
     *
     * @param event 当前事件（同一对象贯穿整个阶段，payload 可跨前置/后置共享）
     * @param ctx   Hook 上下文
     * @param next  下游，可能是内层 AroundHook 或核心 LLM 调用
     * @return 处理后的 event
     */
    default Mono<HookEvent> aroundReasoning(HookEvent event, HookContext ctx,
                                             Function<HookEvent, Mono<HookEvent>> next) {
        return next.apply(event);
    }

    /**
     * 包裹推理阶段（流式）。默认实现直接透传，子类可覆盖。
     *
     * @param event 当前事件
     * @param ctx   Hook 上下文
     * @param next  下游，可能是内层 AroundHook 或核心流式模型调用
     * @return 处理后的流式分块
     */
    default Flux<ChatStreamChunk> aroundReasoningStream(HookEvent event, HookContext ctx,
                                                         Function<HookEvent, Flux<ChatStreamChunk>> next) {
        return next.apply(event);
    }

    /**
     * 包裹单次工具调用（PRE_TOOL_CALL → 工具执行 → POST_TOOL_CALL）。
     */
    default Mono<HookEvent> aroundToolCall(HookEvent event, HookContext ctx,
                                            Function<HookEvent, Mono<HookEvent>> next) {
        return next.apply(event);
    }

    /**
     * 包裹整个 call（非流式）。
     */
    default Mono<HookEvent> aroundCall(HookEvent event, HookContext ctx,
                                        Function<HookEvent, Mono<HookEvent>> next) {
        return next.apply(event);
    }

    /**
     * 包裹整个 call（流式）。默认直接透传，子类可覆盖。
     *
     * @param event 当前事件
     * @param ctx   Hook 上下文
     * @param core  下游，可能是内层 AroundHook 或核心流
     * @return 处理后的流式分块
     */
    default Flux<ChatStreamChunk> aroundCallStream(HookEvent event, HookContext ctx,
                                                    Function<HookEvent, Flux<ChatStreamChunk>> core) {
        return core.apply(event);
    }
}
