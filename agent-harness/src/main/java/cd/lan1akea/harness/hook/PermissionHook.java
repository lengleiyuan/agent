package cd.lan1akea.harness.hook;

import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.tool.Tool;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.function.BiFunction;

/**
 * 业务权限 Hook（门面层，可选注入）。
 * 无权限时返回 HookResult.skip(String) 跳过该工具并继续推理。
 * Tool 实例由框架自动注入到 ToolCallEvent.getTool()，无需自行注入 ToolRegistry。
 *
 * 示例：
 *     HarnessAgent.builder()
 *         .hook(new PermissionHook(
 *             (tool, ctx) -> securityService.hasPermission(ctx.getUserId(), tool.getPermissions())))
 *         .tool(new UserTool())
 *         .build();
 */
public class PermissionHook implements Hook {

    /**
     * Hook 名称。
     */
    private final String name;
    /**
     * 权限校验器，接收 Tool 和 HookContext 返回是否有权限。
     */
    private final BiFunction<Tool, HookContext, Boolean> checker;

    /**
     * 使用默认名称创建权限 Hook。
     */
    public PermissionHook(BiFunction<Tool, HookContext, Boolean> checker) {
        this("HarnessPermissionHook", checker);
    }

    /**
     * 使用自定义名称创建权限 Hook。
     */
    public PermissionHook(String name, BiFunction<Tool, HookContext, Boolean> checker) {
        this.name = name;
        this.checker = checker;
    }

    /**
     * 返回 Hook 名称。
     */
    @Override
    public String getName() { return name; }

    /**
     * 返回订阅的事件类型（PRE_TOOL_CALL）。
     */
    @Override
    public Set<HookEventType> getSubscribedEventTypes() {
        return Set.of(HookEventType.PRE_TOOL_CALL);
    }

    /**
     * 返回优先级，略高于默认值以便尽早拦截。
     */
    @Override
    public int getPriority() { return 8; }

    /**
     * 在工具调用前检查权限，无权限则跳过。
     */
    @Override
    public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
        if (!(event instanceof ToolCallEvent tce)) {
            return Mono.just(HookResult.continue_());
        }

        Tool tool = tce.getTool();
        if (tool == null) return Mono.just(HookResult.continue_());

        Set<String> requiredPermissions = tool.getPermissions();
        if (requiredPermissions.isEmpty()) return Mono.just(HookResult.continue_());

        try {
            boolean allowed = checker.apply(tool, context);
            if (!allowed) {
                return Mono.just(HookResult.skip(
                    "无权限: tool=" + tool.getName() + " 需要 " + requiredPermissions));
            }
        } catch (Exception e) {
            return Mono.just(HookResult.abort("权限校验异常: " + e.getMessage()));
        }

        return Mono.just(HookResult.continue_());
    }
}
