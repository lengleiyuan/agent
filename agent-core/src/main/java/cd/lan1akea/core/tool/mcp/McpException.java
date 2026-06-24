package cd.lan1akea.core.tool.mcp;

/**
 * MCP 调用异常。
 */
public class McpException extends RuntimeException {

    public McpException(String message) {
        super(message);
    }

    public McpException(String message, Throwable cause) {
        super(message, cause);
    }
}
