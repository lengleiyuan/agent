package cd.lan1akea.harness.hook;

import cd.lan1akea.core.hook.*;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * Hook 接口（门面层）。
 * 业务代码实现此接口创建 Hook，无需直接依赖 core 层。
 * 与 core 层 Hook 完全兼容，通过 HarnessAgent.builder().hook(...) 注册。
 *
 * 示例：
 *     public class AuditHook implements IHook {
 *         public String getName() { return "audit"; }
 *         public Set<HookEventType> getSubscribedEventTypes() {
 *             return Set.of(HookEventType.PRE_TOOL_CALL);
 *         }
 *         public Mono<HookResult> onEvent(HookEvent event, HookContext ctx) {
 *             System.out.println("tool: " + ctx.getAgentName());
 *             return Mono.just(HookResult.continue_());
 *         }
 *     }
 */
public interface IHook extends Hook {

    /**
     * 返回 Hook 名称。
     */
    @Override
    String getName();

    /**
     * 返回订阅的事件类型集合。
     */
    @Override
    Set<HookEventType> getSubscribedEventTypes();

    /**
     * 返回该 Hook 是否启用。
     */
    @Override
    default boolean isEnabled() { return true; }

    /**
     * 处理 Hook 事件。
     */
    @Override
    Mono<HookResult> onEvent(HookEvent event, HookContext context);

    /**
     * 返回 Hook 优先级。
     */
    @Override
    default int getPriority() { return 100; }
}
