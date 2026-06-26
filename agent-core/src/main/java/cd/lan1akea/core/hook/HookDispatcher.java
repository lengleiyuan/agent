package cd.lan1akea.core.hook;

import cd.lan1akea.core.hook.recorder.HookRecorder;
import reactor.core.publisher.Mono;

/**
 * Hook 调度器。
 * <p>
 * 将事件分发给 HookChain 执行，并提供统一的调度接口。
 * 可选接入 HookRecorder 进行审计/回放。
 * </p>
 */
public class HookDispatcher {

    private final HookChain hookChain;
    private HookRecorder recorder;

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

        // HookContext 已通过 onEvent(HookEvent, HookContext) 参数传入每个 Hook，
        // 无需额外的 setter 注入——那会在线程间引入竞态条件。
        return hookChain.fire(eventType, event, context)
            .doOnNext(result -> {
                if (recorder != null) {
                    recorder.record("dispatcher", event, result);
                }
            });
    }

    /** 设置 Hook 记录器 */
    public void setRecorder(HookRecorder recorder) {
        this.recorder = recorder;
    }

    /** @return 关联的 Hook 链 */
    public HookChain getHookChain() {
        return hookChain;
    }
}
