package cd.lan1akea.core.tool;

import cd.lan1akea.core.model.ToolSchema;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.Set;

/**
 * 工具顶层接口。
 * 所有工具必须实现此接口。工具通过 name、description、parameters 向 LLM 描述自身，
 * 通过 execute 执行实际逻辑。
 */
public interface Tool {

    /**
     * @return 工具名称（对 LLM 可见，使用蛇形命名如 "web_search"）
     */
    String getName();

    /**
     * @return 工具描述（自然语言，帮助 LLM 决定何时使用）
     */
    String getDescription();

    /**
     * @return 工具参数 Schema
     */
    ToolSchema getParameters();

    /**
     * 执行工具逻辑。
     *
     * @param params 调用参数
     * @return Mono<ToolResult> 执行结果
     */
    Mono<ToolResult> execute(ToolCallContext params);

    /**
     * @return 工具分组名称（默认为 "default"）
     */
    default String getGroup() {
        return "default";
    }

    /**
     * @return 业务权限码集合。框架不强制校验，Hook 实现（如 HarnessPermissionHook）
     *         可读取此值并结合业务权限框架做拦截。默认空集。
     */
    default Set<String> getPermissions() {
        return Collections.emptySet();
    }

    /**
     * 执行超时时间（毫秒），0 表示不超时。
     */
    default long getTimeoutMs() {
        return 30000;
    }

    /**
     * 工具操作的风险等级，用于审批页面展示。
     * 业务方可覆写返回 HIGH / CRITICAL。
     */
    default String getRiskLevel() { return "MEDIUM"; }
}
