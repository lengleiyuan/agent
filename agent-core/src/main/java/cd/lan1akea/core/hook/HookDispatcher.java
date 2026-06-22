package cd.lan1akea.core.hook;

import reactor.core.publisher.Mono;

/**
 * Hook 调度器。
 * <p>
 * 将事件分发给 HookChain 执行，并提供统一的调度接口。
 * </p>
 */
public class HookDispatcher {

    private final HookChain hookChain;

    public HookDispatcher(HookChain hookChain) {
        this.hookChain = hookChain;
    }

    /**
     * 调度事件。
     *
     * @param eventType 事件类型
     * @param event     事件数据
     * @param context   执行上下文
     * @return Mono&lt;HookResult&gt; 处理结果
     */
    public Mono<HookResult> dispatch(HookEventType eventType, HookEvent event, HookContext context) {
        if (hookChain.size() == 0) {
            return Mono.just(HookResult.continue_());
        }
        return hookChain.fire(eventType, event, context);
    }

    /** @return 关联的 Hook 链 */
    public HookChain getHookChain() {
        return hookChain;
    }
}
