package cd.lan1akea.core.tool;

import cd.lan1akea.core.context.RuntimeContext;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 工具调用者身份，从 RuntimeContext 提取，与 LLM 参数分离。
 * 可独立扩展字段，不影响 ToolCallContext 的扁平结构。
 */
public class CallerIdentity {

    /**
     * 租户 ID
     */
    private final String tenantId;
    /**
     * 用户 ID
     */
    private final String userId;
    /**
     * 会话 ID
     */
    private final String sessionId;
    /**
     * Agent 名称
     */
    private final String agentName;
    /**
     * 扩展属性
     */
    private final Map<String, Object> attributes;

    private CallerIdentity(Builder builder) {
        this.tenantId = builder.tenantId;
        this.userId = builder.userId;
        this.sessionId = builder.sessionId;
        this.agentName = builder.agentName;
        this.attributes = builder.attributes != null
            ? Collections.unmodifiableMap(new HashMap<>(builder.attributes))
            : Collections.emptyMap();
    }

    /**
     * 从 RuntimeContext 构造调用者身份。
     *
     * @param ctx 运行时上下文，null 时返回空身份
     * @return 调用者身份
     */
    public static CallerIdentity from(RuntimeContext ctx) {
        if (ctx == null) return new Builder().build();
        return new Builder()
            .tenantId(ctx.getTenantId()).userId(ctx.getUserId())
            .sessionId(ctx.getSessionId()).agentName(ctx.getAgentName())
            .attributes(ctx.getAttributes())
            .build();
    }

    /**
     * @return 新的 Builder 实例
     */
    public static Builder builder() { return new Builder(); }


    /**
     * @return 租户 ID
     */
    public String getTenantId() { return tenantId; }
    /**
     * @return 用户 ID
     */
    public String getUserId() { return userId; }
    /**
     * @return 会话 ID
     */
    public String getSessionId() { return sessionId; }
    /**
     * @return Agent 名称
     */
    public String getAgentName() { return agentName; }
    /**
     * @return 扩展属性（只读）
     */
    public Map<String, Object> getAttributes() { return attributes; }

    /**
     * 获取指定名称的扩展属性。
     *
     * @param key 属性名
     * @param <T> 属性值类型
     * @return 属性值，可能为 null
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) { return (T) attributes.get(key); }

    @Override
    public String toString() {
        return "Identity{tenant=" + tenantId + ", user=" + userId
            + ", session=" + sessionId + "}";
    }


    public static class Builder {
        /**
         * 租户 ID
         */
        private String tenantId;
        /**
         * 用户 ID
         */
        private String userId;
        /**
         * 会话 ID
         */
        private String sessionId;
        /**
         * Agent 名称
         */
        private String agentName;
        /**
         * 扩展属性
         */
        private Map<String, Object> attributes;

        /**
         * @param v 租户 ID
         */
        public Builder tenantId(String v) { this.tenantId = v; return this; }
        /**
         * @param v 用户 ID
         */
        public Builder userId(String v) { this.userId = v; return this; }
        /**
         * @param v 会话 ID
         */
        public Builder sessionId(String v) { this.sessionId = v; return this; }
        /**
         * @param v Agent 名称
         */
        public Builder agentName(String v) { this.agentName = v; return this; }
        /**
         * @param v 扩展属性
         */
        public Builder attributes(Map<String, Object> v) { this.attributes = v; return this; }

        /**
         * 从 RuntimeContext 复制所有身份字段。
         */
        public Builder from(RuntimeContext ctx) {
            if (ctx != null) {
                this.tenantId = ctx.getTenantId();
                this.userId = ctx.getUserId();
                this.sessionId = ctx.getSessionId();
                this.agentName = ctx.getAgentName();
                this.attributes = ctx.getAttributes();
            }
            return this;
        }

        /**
         * @return 构建的 CallerIdentity 实例
         */
        public CallerIdentity build() { return new CallerIdentity(this); }
    }
}
