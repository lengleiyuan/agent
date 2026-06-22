package cd.lan1akea.core.tool;

/**
 * 工具执行上下文提供者接口。
 * <p>
 * 框架在调用工具前注入当前上下文。
 * </p>
 */
@FunctionalInterface
public interface ToolExecutionContextProvider {

    /**
     * @return 当前工具执行上下文
     */
    ToolExecutionContext getContext();
}
