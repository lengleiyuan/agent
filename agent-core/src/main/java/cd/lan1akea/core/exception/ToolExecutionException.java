package cd.lan1akea.core.exception;

/**
 * 工具执行异常，在工具逻辑执行失败时抛出。
 */
public class ToolExecutionException extends AgentException {

    private final String toolName;

    public ToolExecutionException(String toolName, String message) {
        super("TOL_001", "工具 [" + toolName + "] 执行失败: " + message);
        this.toolName = toolName;
    }

    public ToolExecutionException(String toolName, String message, Throwable cause) {
        super("TOL_001", "工具 [" + toolName + "] 执行失败: " + message, cause);
        this.toolName = toolName;
    }

    /** @return 工具名称 */
    public String getToolName() { return toolName; }
}
