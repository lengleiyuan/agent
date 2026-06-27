package cd.lan1akea.core.message;

import cd.lan1akea.core.util.JsonUtils;

import java.util.Collections;
import java.util.Map;

/**
 * 工具调用内容块。
 * LLM 请求调用工具时产出此块，包含工具名称和参数。
 * 构造时解析 JSON 为 Map，后续使用零开销。
 */
public class ToolUseBlock extends ContentBlock {

    /**
     * 工具调用唯一ID
     */
    private final String id;

    /**
     * 工具名称
     */
    private final String name;

    /**
     * 调用参数（JSON字符串，保留用于序列化/日志）
     */
    private final String arguments;

    /**
     * 调用参数（已解析，O(1) 取值）
     */
    private final Map<String, Object> argumentsMap;

    /**
     * 创建工具调用块。
     *
     * @param id        工具调用唯一标识
     * @param name      工具名称
     * @param arguments 工具参数（JSON 字符串）
     */
    @SuppressWarnings("unchecked")
    public ToolUseBlock(String id, String name, String arguments) {
        super(TYPE_TOOL_USE);
        this.id = id;
        this.name = name;
        this.arguments = arguments;
        Map<String, Object> parsed = JsonUtils.fromJson(arguments, Map.class);
        this.argumentsMap = parsed != null ? Collections.unmodifiableMap(parsed) : Collections.emptyMap();
    }

    /**
     * @return 工具调用ID
     */
    public String getId() { return id; }

    /**
     * @return 工具名称
     */
    public String getName() { return name; }

    /**
     * @return 调用参数JSON（原始字符串）
     */
    public String getArguments() { return arguments; }

    /**
     * @return 调用参数 Map（构造时已解析，无需二次 JSON 转换）
     */
    public Map<String, Object> getArgumentsMap() { return argumentsMap; }
}
