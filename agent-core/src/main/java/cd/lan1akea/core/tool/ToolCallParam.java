package cd.lan1akea.core.tool;

import cd.lan1akea.core.util.JsonUtils;

import java.util.Collections;
import java.util.Map;

/**
 * 工具调用参数。
 * <p>
 * 封装 LLM 发出的工具调用参数（JSON 格式），提供类型安全的参数提取方法。
 * </p>
 */
public class ToolCallParam {

    /** 工具调用 ID（对应 ToolUseBlock.id） */
    private final String callId;

    /** 工具名称 */
    private final String toolName;

    /** 原始参数 Map */
    private final Map<String, Object> arguments;

    @SuppressWarnings("unchecked")
    public ToolCallParam(String callId, String toolName, String argumentsJson) {
        this.callId = callId;
        this.toolName = toolName;
        Map<String, Object> parsed = JsonUtils.fromJson(argumentsJson, Map.class);
        this.arguments = parsed != null ? parsed : Collections.emptyMap();
    }

    public ToolCallParam(String callId, String toolName, Map<String, Object> arguments) {
        this.callId = callId;
        this.toolName = toolName;
        this.arguments = arguments != null ? arguments : Collections.emptyMap();
    }

    /** @return 调用ID */
    public String getCallId() { return callId; }

    /** @return 工具名 */
    public String getToolName() { return toolName; }

    /**
     * 获取指定名称的参数值。
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String name) {
        return (T) arguments.get(name);
    }

    /**
     * 获取指定名称的字符串参数。
     */
    public String getString(String name) {
        Object val = arguments.get(name);
        return val != null ? val.toString() : null;
    }

    /**
     * 获取指定名称的数值参数。
     */
    public Number getNumber(String name) {
        Object val = arguments.get(name);
        if (val instanceof Number) {
            return (Number) val;
        }
        return null;
    }

    /**
     * 获取指定名称的布尔参数。
     */
    public Boolean getBoolean(String name) {
        Object val = arguments.get(name);
        if (val instanceof Boolean) {
            return (Boolean) val;
        }
        return null;
    }

    /** @return 所有参数（只读） */
    public Map<String, Object> getArguments() {
        return Collections.unmodifiableMap(arguments);
    }

    /** @return 参数 JSON */
    public String getArgumentsJson() {
        return JsonUtils.toCompactJson(arguments);
    }

    @Override
    public String toString() {
        return "ToolCallParam{callId=" + callId + ", tool=" + toolName
            + ", args=" + arguments + "}";
    }
}
