package cd.lan1akea.core.message;

/**
 * 工具结果内容块。
 * <p>
 * 工具执行完成后，将结果包装为此块返回给 LLM。
 * </p>
 */
public class ToolResultBlock extends ContentBlock {

    /** 对应 ToolUseBlock 的 ID */
    private final String toolUseId;

    /** 工具执行结果（文本或JSON） */
    private final String content;

    /** 是否执行出错 */
    private final boolean isError;

    public ToolResultBlock(String toolUseId, String content, boolean isError) {
        super(TYPE_TOOL_RESULT);
        this.toolUseId = toolUseId;
        this.content = content;
        this.isError = isError;
    }

    /**
     * 创建成功的工具结果块。
     */
    public static ToolResultBlock success(String toolUseId, String content) {
        return new ToolResultBlock(toolUseId, content, false);
    }

    /**
     * 创建失败的工具结果块。
     */
    public static ToolResultBlock error(String toolUseId, String errorMessage) {
        return new ToolResultBlock(toolUseId, errorMessage, true);
    }

    /** @return 工具调用ID */
    public String getToolUseId() { return toolUseId; }

    /** @return 结果内容 */
    public String getContent() { return content; }

    /** @return 是否出错 */
    public boolean isError() { return isError; }
}
