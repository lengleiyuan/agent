package cd.lan1akea.core.message;

/**
 * 工具调用内容块。
 * <p>
 * LLM 请求调用工具时产出此块，包含工具名称和参数。
 * </p>
 */
public class ToolUseBlock extends ContentBlock {

    /** 工具调用唯一ID */
    private final String id;

    /** 工具名称 */
    private final String name;

    /** 调用参数（JSON字符串） */
    private final String arguments;

    public ToolUseBlock(String id, String name, String arguments) {
        super(TYPE_TOOL_USE);
        this.id = id;
        this.name = name;
        this.arguments = arguments;
    }

    /** @return 工具调用ID */
    public String getId() { return id; }

    /** @return 工具名称 */
    public String getName() { return name; }

    /** @return 调用参数JSON */
    public String getArguments() { return arguments; }
}
