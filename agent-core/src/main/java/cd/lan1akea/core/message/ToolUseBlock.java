package cd.lan1akea.core.message;

import cd.lan1akea.core.util.JsonUtils;

import java.util.Collections;
import java.util.Map;

/**
 * 工具调用内容块。
 * LLM 请求调用工具时产出此块，包含工具名称和参数。
 * 使用多级回退解析 JSON，永不因 malformed JSON 崩溃。
 */
public class ToolUseBlock extends ContentBlock {

    private final String id;
    private final String name;
    private final String arguments;
    private final Map<String, Object> argumentsMap;

    public ToolUseBlock(String id, String name, String arguments) {
        super(TYPE_TOOL_USE);
        this.id = id;
        this.name = name;
        this.arguments = arguments;
        this.argumentsMap = JsonUtils.safeParseMap(arguments);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getArguments() { return arguments; }

    /**
     * 调用参数 Map。malformed JSON 时返回空 Map，永不 null。
     */
    public Map<String, Object> getArgumentsMap() { return argumentsMap; }
}
