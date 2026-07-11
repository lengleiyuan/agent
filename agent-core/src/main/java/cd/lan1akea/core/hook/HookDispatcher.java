package cd.lan1akea.core.hook;

import cd.lan1akea.core.hook.recorder.HookRecorder;
import reactor.core.publisher.Mono;

/**
 * Hook 调度器。
 *
 * <p>将事件分发给 HookChain 执行，不包含管线编排逻辑。
 * 管线编排由 {@link HookPipeline} 统一管理。
 * 可选接入 HookRecorder 进行审计/回放。
 */
public class HookDispatcher {

    /** Hook 链实例 */
    private final HookChain hookChain;
    /** 可选的 Hook 记录器 */
    private HookRecorder recorder;

    /**
     * 创建 HookDispatcher。
     *
     * @param hookChain Hook 链
     */
    public HookDispatcher(HookChain hookChain) {
        this.hookChain = hookChain;
    }

    /**
     * 调度事件。事件类型从 event.getHookEventType() 自动提取。
     *
     * @param event   事件数据
     * @param context 执行上下文
     * @return 处理结果
     */
    public Mono<HookResult> dispatch(HookEvent event, HookContext context) {
        HookEventType eventType = event.getHookEventType();
        if (hookChain.size() == 0) {
            return Mono.just(HookResult.continue_());
        }

        return hookChain.fire(eventType, event, context)
            .doOnNext(result -> {
                if (recorder != null) {
                    recorder.record("dispatcher", event, result);
                }
            });
    }

    /**
     * 设置 Hook 记录器。
     *
     * @param recorder Hook 记录器
     */
    public void setRecorder(HookRecorder recorder) {
        this.recorder = recorder;
    }

    /**
     * @return 关联的 Hook 链
     */
    public HookChain getHookChain() {
        return hookChain;
    }
}
