package cd.lan1akea.core.tenant;

import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * 租户上下文持有者。
 * <p>
 * 使用 Reactor Context 传递租户信息，不使用 ThreadLocal（响应式安全）。
 * </p>
 */
public final class TenantContextHolder {

    /** Reactor Context 键 */
    public static final String CONTEXT_KEY = "tenantContext";

    private TenantContextHolder() { }

    /**
     * 将租户上下文写入 Reactor Context。
     *
     * @param context 租户上下文
     * @return Context 实例
     */
    public static Context withTenant(TenantContext context) {
        return Context.of(CONTEXT_KEY, context);
    }

    /**
     * 从 Reactor Context 读取租户上下文。
     *
     * @return Mono&lt;TenantContext&gt;
     */
    public static Mono<TenantContext> getTenant() {
        return Mono.deferContextual(ctx -> {
            if (ctx.hasKey(CONTEXT_KEY)) {
                return Mono.just(ctx.get(CONTEXT_KEY));
            }
            return Mono.empty();
        });
    }

    /**
     * 获取当前租户ID。
     */
    public static Mono<Long> getTenantId() {
        return getTenant().map(TenantContext::getTenantId);
    }
}
