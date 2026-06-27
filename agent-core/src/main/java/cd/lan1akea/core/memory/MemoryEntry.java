package cd.lan1akea.core.memory;

import java.util.*;

/**
 * 记忆条目。
 */
public class MemoryEntry {

    /**
     * 记忆条目的唯一标识。
     */
    private String id;
    /**
     * 租户 ID。
     */
    private String tenantId;
    /**
     * 用户 ID。
     */
    private String userId;
    /**
     * 会话 ID。
     */
    private String sessionId;
    /**
     * 角色（user / assistant / system / tool）。
     */
    private String role;
    /**
     * 文本内容。
     */
    private String content;
    /**
     * 重要性分值（0~1，默认 0.5）。
     */
    private double importance;
    /**
     * 标签集合。
     */
    private Set<String> tags;
    /**
     * 附加元数据。
     */
    private Map<String, Object> metadata;
    /**
     * 创建时间戳（毫秒）。
     */
    private long createdAtEpochMs;

    /**
     * 创建一个新的 MemoryEntry，设置默认时间戳和重要性。
     */
    public MemoryEntry() {
        this.createdAtEpochMs = System.currentTimeMillis();
        this.importance = 0.5;
        this.tags = Collections.emptySet();
        this.metadata = Collections.emptyMap();
    }

    /**
     * 返回记忆 ID。
     */
    public String getId() { return id; }
    /**
     * 设置记忆 ID。
     */
    public void setId(String id) { this.id = id; }

    /**
     * 返回租户 ID。
     */
    public String getTenantId() { return tenantId; }
    /**
     * 设置租户 ID。
     */
    public void setTenantId(String v) { this.tenantId = v; }

    /**
     * 返回用户 ID。
     */
    public String getUserId() { return userId; }
    /**
     * 设置用户 ID。
     */
    public void setUserId(String v) { this.userId = v; }

    /**
     * 返回会话 ID。
     */
    public String getSessionId() { return sessionId; }
    /**
     * 设置会话 ID。
     */
    public void setSessionId(String v) { this.sessionId = v; }

    /**
     * 返回角色。
     */
    public String getRole() { return role; }
    /**
     * 设置角色。
     */
    public void setRole(String v) { this.role = v; }

    /**
     * 返回文本内容。
     */
    public String getContent() { return content; }
    /**
     * 设置文本内容。
     */
    public void setContent(String v) { this.content = v; }

    /**
     * 返回重要性分值。
     */
    public double getImportance() { return importance; }
    /**
     * 设置重要性分值。
     */
    public void setImportance(double v) { this.importance = v; }

    /**
     * 返回标签集合。
     */
    public Set<String> getTags() { return tags; }
    /**
     * 设置标签集合。
     */
    public void setTags(Set<String> v) { this.tags = v != null ? v : Collections.emptySet(); }

    /**
     * 返回附加元数据。
     */
    public Map<String, Object> getMetadata() { return metadata; }
    /**
     * 设置附加元数据。
     */
    public void setMetadata(Map<String, Object> v) { this.metadata = v != null ? v : Collections.emptyMap(); }

    /**
     * 返回创建时间戳（毫秒）。
     */
    public long getCreatedAtEpochMs() { return createdAtEpochMs; }
    /**
     * 设置创建时间戳（毫秒）。
     */
    public void setCreatedAtEpochMs(long v) { this.createdAtEpochMs = v; }


    /**
     * 创建一个新的 Builder。
     */
    public static Builder builder() { return new Builder(); }

    /**
     * MemoryEntry 的建造者。
     */
    public static class Builder {
        /**
         * 正在构建的 MemoryEntry 实例。
         */
        private final MemoryEntry e = new MemoryEntry();
        /**
         * 设置记忆 ID。
         */
        public Builder id(String v) { e.id = v; return this; }
        /**
         * 设置租户 ID。
         */
        public Builder tenantId(String v) { e.tenantId = v; return this; }
        /**
         * 设置用户 ID。
         */
        public Builder userId(String v) { e.userId = v; return this; }
        /**
         * 设置会话 ID。
         */
        public Builder sessionId(String v) { e.sessionId = v; return this; }
        /**
         * 设置角色。
         */
        public Builder role(String v) { e.role = v; return this; }
        /**
         * 设置文本内容。
         */
        public Builder content(String v) { e.content = v; return this; }
        /**
         * 设置重要性分值。
         */
        public Builder importance(double v) { e.importance = v; return this; }
        /**
         * 设置标签集合。
         */
        public Builder tags(Set<String> v) { e.tags = v; return this; }
        /**
         * 设置附加元数据。
         */
        public Builder metadata(Map<String, Object> v) { e.metadata = v; return this; }
        /**
         * 设置创建时间戳（毫秒）。
         */
        public Builder createdAtEpochMs(long v) { e.createdAtEpochMs = v; return this; }
        /**
         * 构建并返回 MemoryEntry。
         */
        public MemoryEntry build() { return e; }
    }
}
