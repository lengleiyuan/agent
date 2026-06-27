package cd.lan1akea.harness.hook;

import cd.lan1akea.core.hook.AroundHook;
import cd.lan1akea.core.hook.HookContext;
import cd.lan1akea.core.hook.HookEvent;
import reactor.core.publisher.Mono;

import java.util.function.Function;

/**
 * 包裹式 Hook 接口（门面层）。
 * 前置和后置逻辑在同一个方法作用域内，局部变量天然线程安全。
 * 与 core 层 AroundHook 完全兼容。
 *
 * 示例：
 *     public class TimingHook implements IAroundHook {
 *         public String getName() { return "timing"; }
 *
 *         public Mono<HookEvent> aroundReasoning(HookEvent event, HookContext ctx,
 *                                                   Function<HookEvent, Mono<HookEvent>> next) {
 *             long start = System.nanoTime();
 *             return next.apply(event)
 *                 .doOnSuccess(e -> log.info("推理耗时: {}ms",
 *                     (System.nanoTime() - start) / 1_000_000));
 *         }
 *     }
 */
public interface IAroundHook extends AroundHook {

    /**
     * 返回 Hook 名称。
     */
    @Override
    String getName();

    /**
     * 包裹推理过程。
     */
    @Override
    default Mono<HookEvent> aroundReasoning(HookEvent event, HookContext ctx,
                                              Function<HookEvent, Mono<HookEvent>> next) {
        return next.apply(event);
    }

    /**
     * 包裹工具调用过程。
     */
    @Override
    default Mono<HookEvent> aroundToolCall(HookEvent event, HookContext ctx,
                                             Function<HookEvent, Mono<HookEvent>> next) {
        return next.apply(event);
    }

    /**
     * 包裹完整的调用过程。
     */
    @Override
    default Mono<HookEvent> aroundCall(HookEvent event, HookContext ctx,
                                         Function<HookEvent, Mono<HookEvent>> next) {
        return next.apply(event);
    }
}
