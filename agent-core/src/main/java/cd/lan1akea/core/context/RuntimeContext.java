package cd.lan1akea.core.context;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 运行时上下文。
 * <p>
 * 每次调用 chat/stream 时传入，携带当前请求的运行时参数。
 * 优先于 Reactor Context 中的隐式值。
 * </p>
 *
 * <pre>{@code
 * RuntimeContext ctx = RuntimeContext.builder()
 *     .sessionId("sess-123").tenantId("tenant-A").userId("user-1")
 *     .build();
 * agent.chat(messages, ctx);
 * }</pre>
 */
public class RuntimeContext {

    private final String tenantId;
    private final String userId;
    private final String sessionId;
    private final String agentName;
    private final Map<String, Object> attributes;

    public RuntimeContext(String tenantId, String userId, String sessionId,
                           String agentName, Map<String, Object> attributes) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.sessionId = sessionId;
        this.agentName = agentName;
        this.attributes = attributes != null
            ? Collections.unmodifiableMap(new HashMap<>(attributes))
            : Collections.emptyMap();
    }

    public String getTenantId() { return tenantId; }
    public String getUserId() { return userId; }
    public String getSessionId() { return sessionId; }
    public String getAgentName() { return agentName; }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) { return (T) attributes.get(key); }

    public Map<String, Object> getAttributes() { return attributes; }

    /** @return 空上下文 */
    public static RuntimeContext empty() {
        return new RuntimeContext(null, null, null, null, null);
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String tenantId;
        private String userId;
        private String sessionId;
        private String agentName;
        private final Map<String, Object> attributes = new HashMap<>();

        public Builder tenantId(String v) { this.tenantId = v; return this; }
        public Builder userId(String v) { this.userId = v; return this; }
        public Builder sessionId(String v) { this.sessionId = v; return this; }
        public Builder agentName(String v) { this.agentName = v; return this; }
        public Builder attribute(String key, Object value) { this.attributes.put(key, value); return this; }

        public RuntimeContext build() {
            return new RuntimeContext(tenantId, userId, sessionId, agentName, attributes);
        }
    }
}
