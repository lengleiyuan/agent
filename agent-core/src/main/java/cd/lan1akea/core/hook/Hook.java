package cd.lan1akea.core.hook;

import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * Hook 顶层接口。
 * <p>
 * 所有 Hook 实现必须实现此接口。
 * 通过 onEvent 处理事件，返回 HookResult 决定后续行为。
 * </p>
 */
public interface Hook {

    /**
     * @return Hook 唯一名称
     */
    String getName();

    /**
     * @return 监听的事件类型集合
     */
    Set<HookEventType> getSubscribedEventTypes();

    /**
     * Hook 是否启用。
     */
    default boolean isEnabled() {
        return true;
    }

    /**
     * 处理 Hook 事件。
     *
     * @param event   事件数据
     * @param context Hook 执行上下文
     * @return Mono&lt;HookResult&gt; 处理结果
     */
    Mono<HookResult> onEvent(HookEvent event, HookContext context);

    /**
     * Hook 优先级（数值越小优先级越高，默认 100）。
     */
    default int getPriority() {
        return 100;
    }
}
