package cd.lan1akea.core.tool;

/**
 * 工具执行结果。
 * <p>
 * 包含执行状态（成功/失败）、结果内容、以及可选的错误信息。
 * </p>
 */
public class ToolResult {

    /** 是否执行成功 */
    private final boolean success;

    /** 结果内容（文本或 JSON） */
    private final String content;

    /** 错误信息（失败时有效） */
    private final String errorMessage;

    private ToolResult(boolean success, String content, String errorMessage) {
        this.success = success;
        this.content = content;
        this.errorMessage = errorMessage;
    }

    /** 创建成功结果 */
    public static ToolResult success(String content) {
        return new ToolResult(true, content, null);
    }

    /** 创建失败结果 */
    public static ToolResult failure(String errorMessage) {
        return new ToolResult(false, null, errorMessage);
    }

    /** @return 是否成功 */
    public boolean isSuccess() { return success; }

    /** @return 结果内容 */
    public String getContent() { return content; }

    /** @return 错误信息 */
    public String getErrorMessage() { return errorMessage; }
}
