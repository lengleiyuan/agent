package cd.lan1akea.core.hook;

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
     * 四参数函数式接口，Hook 的阶段方法（如 aroundReasoning）。
     */
    @FunctionalInterface
    private interface WrapFunction {
        Mono<HookEvent> apply(AroundHook hook, HookEvent event, HookContext ctx,
                              Function<HookEvent, Mono<HookEvent>> next);
    }

    /**
     * 洋葱构建：逆序遍历 hooks 列表，从最内层往外包裹。
     * hooks = [A, B, C]，核心操作 = core：
     * A.wrap(e0, ctx, e1 ->
     *   B.wrap(e1, ctx, e2 ->
     *     C.wrap(e2, ctx, core)))
     */
    private Mono<HookEvent> wrap(HookEvent event, HookContext ctx,
                                  Function<HookEvent, Mono<HookEvent>> core,
                                  WrapFunction wf) {
        // 从内向外构建：chain 初始为 core，每次用外层 hook 包裹
        Function<HookEvent, Mono<HookEvent>> chain = core;
        for (int i = hooks.size() - 1; i >= 0; i--) {
            final AroundHook hook = hooks.get(i);
            final Function<HookEvent, Mono<HookEvent>> inner = chain;
            chain = (HookEvent e) -> wf.apply(hook, e, ctx, inner);
        }
        return chain.apply(event);
    }
}
