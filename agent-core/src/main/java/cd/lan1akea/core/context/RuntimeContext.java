package cd.lan1akea.core.context;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 通用运行时上下文。
 * <p>
 * 携带一次请求执行所需的环境信息：租户、用户、会话、模型信息等。
 * </p>
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
}
