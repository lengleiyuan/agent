package cd.lan1akea.core.exception;

/**
 * 工具执行异常，在工具逻辑执行失败时抛出。
 */
public class ToolExecutionException extends AgentException {

    /**
     * 执行失败的工具名称。
     */
    private final String toolName;

    /**
     * 创建工具执行异常。
     *
     * @param toolName 执行失败的工具名称
     * @param message  错误描述
     */
    public ToolExecutionException(String toolName, String message) {
        super("TOL_001", "工具 [" + toolName + "] 执行失败: " + message);
        this.toolName = toolName;
    }

    /**
     * 创建带原因的工具执行异常。
     *
     * @param toolName 执行失败的工具名称
     * @param message  错误描述
     * @param cause    根原因
     */
    public ToolExecutionException(String toolName, String message, Throwable cause) {
        super("TOL_001", "工具 [" + toolName + "] 执行失败: " + message, cause);
        this.toolName = toolName;
    }

    /**
     * @return 工具名称
     */
    public String getToolName() { return toolName; }
}
