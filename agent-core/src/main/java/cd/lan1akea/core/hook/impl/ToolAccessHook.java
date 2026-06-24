package cd.lan1akea.core.hook.impl;

import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.tool.ToolAccessPolicy;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * 工具访问控制 Hook（PreToolCallHook）。
 * <p>
 * 基于 {@link ToolAccessPolicy} 在工具执行前校验权限：
 * <ul>
 * <li>allowlist 模式：只有列表内的工具可调用</li>
 * <li>blocklist 模式：列表内的工具被禁止</li>
 * </ul>
 * 优先级设为 5（在 RateLimitHook 之后、其他 Hook 之前），尽早拦截。
 * </p>
 */
public class ToolAccessHook implements Hook {

    private final String name;
    private final ToolAccessPolicy policy;

    public ToolAccessHook(ToolAccessPolicy policy) {
        this("ToolAccessHook", policy);
    }

    public ToolAccessHook(String name, ToolAccessPolicy policy) {
        this.name = name;
        this.policy = policy;
    }

    @Override
    public String getName() { return name; }

    @Override
    public Set<HookEventType> getSubscribedEventTypes() {
        return Set.of(HookEventType.PRE_TOOL_CALL);
    }

    @Override
    public int getPriority() { return 5; } // 早于 RateLimitHook(10)

    @Override
    public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
        if (!(event instanceof ToolCallEvent tce)) {
            return Mono.just(HookResult.continue_());
        }

        String tenantId = context != null ? context.getTenantId() : null;
        String toolName = tce.getCallParam() != null
            ? tce.getCallParam().getToolName() : "unknown";

        if (!policy.isAllowed(tenantId, toolName)) {
            return Mono.just(HookResult.abort(
                "租户 [" + tenantId + "] 无权调用工具 [" + toolName + "]"));
        }

        return Mono.just(HookResult.continue_());
    }
}
