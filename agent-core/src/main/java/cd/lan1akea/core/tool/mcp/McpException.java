package cd.lan1akea.core.tool.mcp;

/**
 * MCP 调用异常。
 */
public class McpException extends RuntimeException {

    /**
     * 使用错误消息创建异常。
     *
     * @param message 错误描述
     */
    public McpException(String message) {
        super(message);
    }

    /**
     * 使用错误消息和原因创建异常。
     *
     * @param message 错误描述
     * @param cause   原始异常
     */
    public McpException(String message, Throwable cause) {
        super(message, cause);
    }
}
