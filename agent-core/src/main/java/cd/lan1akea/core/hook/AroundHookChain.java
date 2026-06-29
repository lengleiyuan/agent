package cd.lan1akea.core.hook;

import cd.lan1akea.core.model.ChatStreamChunk;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * AroundHook 链，将多个 AroundHook 按注册顺序构建为洋葱包裹。
 * 包裹顺序：先注册的在最外层（先执行前置，最后执行后置）：
 * Hook A (先注册)
 *   └─ Hook B
 *        └─ Hook C (最后注册)
 *             └─ 核心操作 (LLM调用 / 工具执行)
 */
public class AroundHookChain {

    private final List<AroundHook> hooks;

    /**
     * 创建空 AroundHook 链。
     */
    public AroundHookChain() {
        this.hooks = new ArrayList<>();
    }

    /**
     * 创建包含指定 Hook 列表的 AroundHook 链。
     */
    public AroundHookChain(List<AroundHook> hooks) {
        this.hooks = new ArrayList<>(hooks);
    }

    /**
     * 注册 AroundHook。
     */
    public void register(AroundHook hook) {
        this.hooks.add(hook);
    }

    /**
     * 按名称注销 AroundHook。
     */
    public void unregister(String name) {
        hooks.removeIf(h -> h.getName().equals(name));
    }

    /**
     * @return AroundHook 数量
     */
    public int size() {
        return hooks.size();
    }

    /**
     * @return 是否为空
     */
    public boolean isEmpty() {
        return hooks.isEmpty();
    }

    /**
     * @return 只读 AroundHook 列表
     */
    public List<AroundHook> getHooks() {
        return Collections.unmodifiableList(hooks);
    }


    /**
     * 包裹推理阶段。
     */
    public Mono<HookEvent> aroundReasoning(HookEvent event, HookContext ctx,
                                            Function<HookEvent, Mono<HookEvent>> core) {
        if (hooks.isEmpty()) return core.apply(event);
        return wrap(event, ctx, core, (hook, e, c, next) -> hook.aroundReasoning(e, c, next));
    }

    /**
     * 包裹单次工具调用。
     */
    public Mono<HookEvent> aroundToolCall(HookEvent event, HookContext ctx,
                                           Function<HookEvent, Mono<HookEvent>> core) {
        if (hooks.isEmpty()) return core.apply(event);
        return wrap(event, ctx, core, (hook, e, c, next) -> hook.aroundToolCall(e, c, next));
    }

    /**
     * 包裹整个 call。
     */
    public Mono<HookEvent> aroundCall(HookEvent event, HookContext ctx,
                                       Function<HookEvent, Mono<HookEvent>> core) {
        if (hooks.isEmpty()) return core.apply(event);
        return wrap(event, ctx, core, (hook, e, c, next) -> hook.aroundCall(e, c, next));
    }


    /**
     * 包裹整个 call（流式）。
     */
    public Flux<ChatStreamChunk> aroundCallStream(HookEvent event, HookContext ctx,
                                                   Function<HookEvent, Flux<ChatStreamChunk>> core) {
        if (hooks.isEmpty()) return core.apply(event);
        return wrapStream(event, ctx, core, AroundHook::aroundCallStream);
    }

    /**
     * 包裹推理阶段（流式）。
     */
    public Flux<ChatStreamChunk> aroundReasoningStream(HookEvent event, HookContext ctx,
                                                        Function<HookEvent, Flux<ChatStreamChunk>> core) {
        if (hooks.isEmpty()) return core.apply(event);
        return wrapStream(event, ctx, core, AroundHook::aroundReasoningStream);
    }

    /**
     * 洋葱构建（流式版）。Function 链天然延迟求值，无需 defer。
     */
    private Flux<ChatStreamChunk> wrapStream(HookEvent event, HookContext ctx,
                                              Function<HookEvent, Flux<ChatStreamChunk>> core,
                                              StreamWrapFunction wf) {
        Function<HookEvent, Flux<ChatStreamChunk>> chain = core;
        for (int i = hooks.size() - 1; i >= 0; i--) {
            final AroundHook hook = hooks.get(i);
            final Function<HookEvent, Flux<ChatStreamChunk>> inner = chain;
            chain = e -> wf.apply(hook, e, ctx, inner);
        }
        return chain.apply(event);
    }

    @FunctionalInterface
    private interface StreamWrapFunction {
        Flux<ChatStreamChunk> apply(AroundHook hook, HookEvent event, HookContext ctx,
                                     Function<HookEvent, Flux<ChatStreamChunk>> next);
    }

    @FunctionalInterface
    private interface WrapFunction {
        Mono<HookEvent> apply(AroundHook hook, HookEvent event, HookContext ctx,
                              Function<HookEvent, Mono<HookEvent>> next);
    }

    private Mono<HookEvent> wrap(HookEvent event, HookContext ctx,
                                  Function<HookEvent, Mono<HookEvent>> core,
                                  WrapFunction wf) {
        Function<HookEvent, Mono<HookEvent>> chain = core;
        for (int i = hooks.size() - 1; i >= 0; i--) {
            final AroundHook hook = hooks.get(i);
            final Function<HookEvent, Mono<HookEvent>> inner = chain;
            chain = (HookEvent e) -> wf.apply(hook, e, ctx, inner);
        }
        return chain.apply(event);
    }
}
