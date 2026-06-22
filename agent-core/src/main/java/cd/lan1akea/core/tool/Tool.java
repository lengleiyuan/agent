package cd.lan1akea.core.tool;

import cd.lan1akea.core.model.ToolSchema;
import reactor.core.publisher.Mono;

/**
 * 工具顶层接口。
 * <p>
 * 所有工具必须实现此接口。工具通过 name() + description() + parameters() 向 LLM 描述自身，
 * 通过 execute() 执行实际逻辑。
 * </p>
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
     * @return Mono&lt;ToolResult&gt; 执行结果
     */
    Mono<ToolResult> execute(ToolCallParam params);

    /**
     * @return 工具分组名称（默认为 "default"）
     */
    default String getGroup() {
        return "default";
    }

    /**
     * 是否需要人工审批。
     */
    default boolean requiresApproval() {
        return false;
    }

    /**
     * 执行超时时间（毫秒），0 表示不超时。
     */
    default long getTimeoutMs() {
        return 30000;
    }
}
