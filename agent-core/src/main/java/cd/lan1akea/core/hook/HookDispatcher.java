package cd.lan1akea.core.hook;

import cd.lan1akea.core.CoreConstants.HookSource;
import cd.lan1akea.core.exception.HookAbortException;
import cd.lan1akea.core.hook.recorder.HookRecorder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.function.Function;

/**
 * Hook 调度器。
 * 将事件分发给 HookChain 执行，并提供统一的调度接口。
 * 可选接入 HookRecorder 进行审计/回放。
 */
public class HookDispatcher {

    /**
     * Hook 链实例
     */
    private final HookChain hookChain;
    /**
     * 可选的 Hook 记录器
     */
    private HookRecorder recorder;

    /**
     * 创建 HookDispatcher。
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
     * 设置 Hook 记录器
     */
    public void setRecorder(HookRecorder recorder) {
        this.recorder = recorder;
    }

    /**
     * 管线模板：dispatch pre-hook → 处理 abort/interrupt → 执行 core → fire post-hook。
     * post-hook 为 fire-and-forget 模式，不影响主流程。
     *
     * @param <T>      Flux 元素类型
     * @param preEvent pre-hook 事件
     * @param ctx      Hook 上下文
     * @param core     核心操作（在 pre-hook continue 后执行）
     * @param postType post-hook 事件类型（fire-and-forget）
     * @return 核心操作的 Flux，后接 post-hook 完成信号
     */
    public <T> Flux<T> dispatchAndExecuteStream(HookEvent preEvent, HookContext ctx,
                                                 Function<HookEvent, Flux<T>> core,
                                                 HookEventType postType) {
        return dispatch(preEvent, ctx)
                .flatMapMany(r -> {
                    if (r.isAbort()) {
                        return Flux.error(new HookAbortException(
                                HookSource.HOOK, r.getAbortReason()));
                    }
                    if (r.isInterrupt() || r.isSkip()) {
                        return Flux.empty();
                    }
                    return core.apply(preEvent)
                            .concatWith(dispatch(new HookEvent(postType), ctx)
                                    .then(Mono.<T>empty()).flux());
                });
    }

    /**
     * @return 关联的 Hook 链
     */
    public HookChain getHookChain() {
        return hookChain;
    }
}
