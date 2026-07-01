package cd.lan1akea.core.tool;

/**
 * 工具执行结果。
 * 包含执行状态（成功/失败）、结果内容、可选的错误信息、以及关联的工具调用 ID。
 */
public class ToolResult {

    /**
     * 是否执行成功
     */
    private final boolean success;

    /**
     * 结果内容（文本或 JSON）
     */
    private final String content;

    /**
     * 错误信息（失败时有效）
     */
    private final String errorMessage;

    /**
     * 关联的工具调用 ID（框架层填充，工具实现无需关心）
     */
    private final String callId;

    private ToolResult(boolean success, String content, String errorMessage) {
        this(success, content, errorMessage, null);
    }

    private ToolResult(boolean success, String content, String errorMessage, String callId) {
        this.success = success;
        this.content = content;
        this.errorMessage = errorMessage;
        this.callId = callId;
    }

    /**
     * 创建成功结果。
     *
     * @param content 结果内容
     * @return 成功的结果
     */
    public static ToolResult success(String content) {
        return new ToolResult(true, content, null);
    }

    /**
     * 创建失败结果。
     *
     * @param errorMessage 错误信息
     * @return 失败的结果
     */
    public static ToolResult failure(String errorMessage) {
        return new ToolResult(false, null, errorMessage);
    }

    /**
     * 创建带调用 ID 的成功结果。
     *
     * @param callId  工具调用 ID
     * @param content 结果内容
     * @return 成功的结果
     */
    public static ToolResult success(String callId, String content) {
        return new ToolResult(true, content, null, callId);
    }

    /**
     * 创建带调用 ID 的失败结果。
     *
     * @param callId       工具调用 ID
     * @param errorMessage 错误信息
     * @return 失败的结果
     */
    public static ToolResult failure(String callId, String errorMessage) {
        return new ToolResult(false, null, errorMessage, callId);
    }

    /**
     * 返回附加了给定调用 ID 的新 ToolResult（原实例不变）。
     *
     * @param callId 工具调用 ID
     * @return 带有调用 ID 的新 ToolResult
     */
    public ToolResult withCallId(String callId) {
        if (this.callId != null && this.callId.equals(callId)) return this;
        return new ToolResult(this.success, this.content, this.errorMessage, callId);
    }

    /**
     * @return 是否成功
     */
    public boolean isSuccess() { return success; }

    /**
     * @return 结果内容
     */
    public String getContent() { return content; }

    /**
     * @return 错误信息
     */
    public String getErrorMessage() { return errorMessage; }

    /**
     * @return 关联的工具调用 ID，可能为 null
     */
    public String getCallId() { return callId; }
}
