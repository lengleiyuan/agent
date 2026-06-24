package cd.lan1akea.core.tool;

import cd.lan1akea.core.util.JsonUtils;

import java.util.Collections;
import java.util.Map;

/**
 * 工具调用参数。
 * <p>
 * 封装 LLM 发出的工具调用参数（JSON 格式），提供类型安全的参数提取方法。
 * 同时携带运行时上下文（租户/用户/会话）供子 Agent 等场景使用。
 * </p>
 */
public class ToolCallParam {

    /** 工具调用 ID（对应 ToolUseBlock.id） */
    private final String callId;

    /** 工具名称 */
    private final String toolName;

    /** 原始参数 Map */
    private final Map<String, Object> arguments;

    /** 租户 ID（从父 Agent 上下文传递） */
    private final String tenantId;

    /** 用户 ID（从父 Agent 上下文传递） */
    private final String userId;

    /** 会话 ID（从父 Agent 上下文传递） */
    private final String sessionId;

    /** 扩展属性（从 RuntimeContext.attributes 传递） */
    private final Map<String, Object> attributes;

    // ========================================================================
    // 构造函数
    // ========================================================================

    @SuppressWarnings("unchecked")
    public ToolCallParam(String callId, String toolName, String argumentsJson) {
        this(callId, toolName, argumentsJson, null, null, null, null);
    }

    public ToolCallParam(String callId, String toolName, Map<String, Object> arguments) {
        this(callId, toolName, arguments, null, null, null, null);
    }

    /**
     * 完整构造函数（带运行时上下文）。
     */
    public ToolCallParam(String callId, String toolName, String argumentsJson,
                         String tenantId, String userId, String sessionId) {
        this(callId, toolName, argumentsJson, tenantId, userId, sessionId, null);
    }

    public ToolCallParam(String callId, String toolName, String argumentsJson,
                         String tenantId, String userId, String sessionId,
                         Map<String, Object> attributes) {
        this.callId = callId;
        this.toolName = toolName;
        Map<String, Object> parsed = JsonUtils.fromJson(argumentsJson, Map.class);
        this.arguments = parsed != null ? parsed : Collections.emptyMap();
        this.tenantId = tenantId;
        this.userId = userId;
        this.sessionId = sessionId;
        this.attributes = attributes != null ? Collections.unmodifiableMap(attributes) : Collections.emptyMap();
    }

    /**
     * 完整构造函数（带运行时上下文 + Map参数）。
     */
    public ToolCallParam(String callId, String toolName, Map<String, Object> arguments,
                         String tenantId, String userId, String sessionId) {
        this(callId, toolName, arguments, tenantId, userId, sessionId, null);
    }

    public ToolCallParam(String callId, String toolName, Map<String, Object> arguments,
                         String tenantId, String userId, String sessionId,
                         Map<String, Object> attributes) {
        this.callId = callId;
        this.toolName = toolName;
        this.arguments = arguments != null ? arguments : Collections.emptyMap();
        this.tenantId = tenantId;
        this.userId = userId;
        this.sessionId = sessionId;
        this.attributes = attributes != null ? Collections.unmodifiableMap(attributes) : Collections.emptyMap();
    }

    // ========================================================================
    // Getters
    // ========================================================================

    public String getCallId() { return callId; }
    public String getToolName() { return toolName; }
    public String getTenantId() { return tenantId; }
    public String getUserId() { return userId; }
    public String getSessionId() { return sessionId; }
    public Map<String, Object> getAttributes() { return attributes; }
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) { return (T) attributes.get(key); }

    @SuppressWarnings("unchecked")
    public <T> T get(String name) {
        return (T) arguments.get(name);
    }

    public String getString(String name) {
        Object val = arguments.get(name);
        return val != null ? val.toString() : null;
    }

    public Number getNumber(String name) {
        Object val = arguments.get(name);
        if (val instanceof Number) return (Number) val;
        return null;
    }

    public Boolean getBoolean(String name) {
        Object val = arguments.get(name);
        if (val instanceof Boolean) return (Boolean) val;
        return null;
    }

    public Map<String, Object> getArguments() {
        return Collections.unmodifiableMap(arguments);
    }

    public String getArgumentsJson() {
        return JsonUtils.toCompactJson(arguments);
    }

    @Override
    public String toString() {
        return "ToolCallParam{callId=" + callId + ", tool=" + toolName
            + ", tenant=" + tenantId + ", user=" + userId
            + ", args=" + arguments + "}";
    }
}
