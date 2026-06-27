package cd.lan1akea.core.hook;

import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Hook 链（责任链模式）。
 * 持有所有注册的 Hook，按优先级排序。
 * 触发事件时按顺序执行匹配该事件类型的 Hook。
 */
public class HookChain {

    /**
     * 已排序的 Hook 列表
     */
    private final List<Hook> hooks;

    /**
     * 创建空 Hook 链。
     */
    public HookChain() {
        this.hooks = new ArrayList<>();
    }

    /**
     * 创建包含指定 Hook 列表的 Hook 链并自动排序。
     */
    public HookChain(List<Hook> hooks) {
        this.hooks = new ArrayList<>(hooks);
        this.hooks.sort(Comparator.comparingInt(Hook::getPriority));
    }

    /**
     * 注册 Hook。
     *
     * @param hook Hook 实例
     */
    public void register(Hook hook) {
        this.hooks.add(hook);
        this.hooks.sort(Comparator.comparingInt(Hook::getPriority));
    }

    /**
     * 注销 Hook。
     *
     * @param hookName Hook 名称
     */
    public void unregister(String hookName) {
        hooks.removeIf(h -> h.getName().equals(hookName));
    }

    /**
     * 触发 Hook 链。
     * 链式执行所有匹配事件类型的 Hook。
     * 如果某个 Hook 返回 ABORT，立即终止后续 Hook。
     * 如果某个 Hook 返回 INTERRUPT，保存状态并终止。
     *
     * @param eventType 事件类型
     * @param event     事件数据
     * @param context   执行上下文
     * @return 最终处理结果
     */
    public Mono<HookResult> fire(HookEventType eventType, HookEvent event, HookContext context) {
        return Mono.defer(() -> {
            HookResult finalResult = HookResult.continue_();

            return executeHooks(eventType, event, context, finalResult, 0)
                .defaultIfEmpty(finalResult);
        });
    }

    /**
     * 递归执行 Hook 链中匹配事件类型的 Hook。
     */
    private Mono<HookResult> executeHooks(HookEventType eventType, HookEvent event,
                                           HookContext context, HookResult accumulated, int index) {
        if (index >= hooks.size()) {
            return Mono.just(accumulated);
        }

        Hook hook = hooks.get(index);

        // 跳过不匹配的 Hook（支持多事件类型匹配）
        if (!hook.getSubscribedEventTypes().contains(eventType) || !hook.isEnabled()) {
            return executeHooks(eventType, event, context, accumulated, index + 1);
        }

        return hook.onEvent(event, context)
            .flatMap(result -> {
                if (result.isAbort() || result.isInterrupt() || result.isSkip()) {
                    return Mono.just(result);
                }
                // 继续下一个 Hook
                HookResult merged = result.isModify() ? result : accumulated;
                return executeHooks(eventType, event, context, merged, index + 1);
            });
    }

    /**
     * @return Hook 数量
     */
    public int size() { return hooks.size(); }

    /**
     * @return 只读 Hook 列表
     */
    public List<Hook> getHooks() { return new ArrayList<>(hooks); }
}
