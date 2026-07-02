package cd.lan1akea.core.context;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 运行时上下文。
 * 每次调用 chat/stream 时传入，携带当前请求的运行时参数。
 * 优先于 Reactor Context 中的隐式值。
 *
 * 使用示例：
 * RuntimeContext ctx = RuntimeContext.builder()
 *     .sessionId("sess-123").tenantId("tenant-A").userId("user-1")
 *     .build();
 * agent.chat(messages, ctx);
 */
public class RuntimeContext {

    /**
     * 请求追踪 ID。未传入时自动生成 UUID。
     */
    private final String requestId;
    /**
     * 租户标识。
     */
    private final String tenantId;
    /**
     * 用户标识。
     */
    private final String userId;
    /**
     * 会话标识。
     */
    private final String sessionId;
    /**
     * Agent 名称。
     */
    private final String agentName;
    /**
     * 额外的上下文属性。
     */
    private final Map<String, Object> attributes;

    /**
     * 创建运行时上下文。
     *
     * @param tenantId  租户标识
     * @param userId    用户标识
     * @param sessionId 会话标识
     * @param agentName Agent 名称
     * @param attributes 额外的上下文属性
     */
    public RuntimeContext(String tenantId, String userId, String sessionId,
                           String agentName, Map<String, Object> attributes) {
        this(null, tenantId, userId, sessionId, agentName, attributes);
    }

    /**
     * 创建运行时上下文（含 requestId）。
     */
    public RuntimeContext(String requestId, String tenantId, String userId, String sessionId,
                           String agentName, Map<String, Object> attributes) {
        this.requestId = requestId != null ? requestId : java.util.UUID.randomUUID().toString();
        this.tenantId = tenantId;
        this.userId = userId;
        this.sessionId = sessionId;
        this.agentName = agentName;
        this.attributes = attributes != null
            ? Collections.unmodifiableMap(new HashMap<>(attributes))
            : Collections.emptyMap();
    }

    /**
     * @return 请求追踪 ID
     */
    public String getRequestId() { return requestId; }
    /**
     * @return 租户标识
     */
    public String getTenantId() { return tenantId; }
    /**
     * @return 用户标识
     */
    public String getUserId() { return userId; }
    /**
     * @return 会话标识
     */
    public String getSessionId() { return sessionId; }
    /**
     * @return Agent 名称
     */
    public String getAgentName() { return agentName; }

    /**
     * 根据键获取属性。
     *
     * @param key 属性键
     * @param <T> 期望类型
     * @return 属性值，未找到时返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) { return (T) attributes.get(key); }

    /**
     * @return 属性映射（不可修改）
     */
    public Map<String, Object> getAttributes() { return attributes; }

    /**
     * 返回空的运行时上下文。
     *
     * @return 空的 RuntimeContext
     */
    public static RuntimeContext empty() {
        return new RuntimeContext(null, null, null, null, null);
    }

    /**
     * 创建 RuntimeContext 的 Builder。
     *
     * @return 新的 Builder
     */
    public static Builder builder() { return new Builder(); }

    /**
     * RuntimeContext 的建造者。
     */
    public static class Builder {
        private String requestId;
        private String tenantId;
        private String userId;
        private String sessionId;
        private String agentName;
        private final Map<String, Object> attributes = new HashMap<>();

        /**
         * 设置请求追踪 ID（可选，默认自动生成 UUID）。
         */
        public Builder requestId(String v) { this.requestId = v; return this; }
        /**
         * 设置租户 ID。
         */
        public Builder tenantId(String v) { this.tenantId = v; return this; }
        /**
         * 设置用户 ID。
         */
        public Builder userId(String v) { this.userId = v; return this; }
        /**
         * 设置会话 ID。
         */
        public Builder sessionId(String v) { this.sessionId = v; return this; }
        /**
         * 设置 Agent 名称。
         */
        public Builder agentName(String v) { this.agentName = v; return this; }
        /**
         * 添加属性。
         */
        public Builder attribute(String key, Object value) { this.attributes.put(key, value); return this; }

        /**
         * 构建 RuntimeContext。
         *
         * @return 新的 RuntimeContext
         */
        public RuntimeContext build() {
            return new RuntimeContext(requestId, tenantId, userId, sessionId, agentName, attributes);
        }
    }
}
