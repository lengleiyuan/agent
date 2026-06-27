package cd.lan1akea.core.memory;

import java.util.Collections;
import java.util.Set;

/**
 * 记忆检索查询。
 */
public class MemoryRetrievalQuery {

    /**
     * 检索查询文本。
     */
    private String query;
    /**
     * 最大返回结果数，默认 10。
     */
    private int maxResults = 10;
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
     * 角色过滤。
     */
    private String role;
    /**
     * 标签过滤。
     */
    private Set<String> tags;
    /**
     * 最低重要性分值。
     */
    private Double minImportance;
    /**
     * 开始时间戳（毫秒）。
     */
    private Long fromEpochMs;
    /**
     * 结束时间戳（毫秒）。
     */
    private Long toEpochMs;

    /**
     * 创建一个空的 MemoryRetrievalQuery。
     */
    public MemoryRetrievalQuery() {}

    /**
     * 创建一个带有必填参数的 MemoryRetrievalQuery。
     *
     * @param query      检索文本
     * @param maxResults 最大结果数
     * @param tenantId   租户 ID
     * @param userId     用户 ID
     */
    public MemoryRetrievalQuery(String query, int maxResults, String tenantId, String userId) {
        this.query = query;
        this.maxResults = maxResults;
        this.tenantId = tenantId;
        this.userId = userId;
    }


    /**
     * 返回检索查询文本。
     */
    public String getQuery() { return query; }
    /**
     * 设置检索查询文本。
     */
    public void setQuery(String v) { this.query = v; }

    /**
     * 返回最大结果数。
     */
    public int getMaxResults() { return maxResults; }
    /**
     * 设置最大结果数。
     */
    public void setMaxResults(int v) { this.maxResults = v; }

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
     * 返回角色过滤。
     */
    public String getRole() { return role; }
    /**
     * 设置角色过滤。
     */
    public void setRole(String v) { this.role = v; }

    /**
     * 返回标签过滤集合。
     */
    public Set<String> getTags() { return tags != null ? tags : Collections.emptySet(); }
    /**
     * 设置标签过滤集合。
     */
    public void setTags(Set<String> v) { this.tags = v; }

    /**
     * 返回最低重要性分值。
     */
    public Double getMinImportance() { return minImportance; }
    /**
     * 设置最低重要性分值。
     */
    public void setMinImportance(Double v) { this.minImportance = v; }

    /**
     * 返回开始时间戳（毫秒）。
     */
    public Long getFromEpochMs() { return fromEpochMs; }
    /**
     * 设置开始时间戳（毫秒）。
     */
    public void setFromEpochMs(Long v) { this.fromEpochMs = v; }

    /**
     * 返回结束时间戳（毫秒）。
     */
    public Long getToEpochMs() { return toEpochMs; }
    /**
     * 设置结束时间戳（毫秒）。
     */
    public void setToEpochMs(Long v) { this.toEpochMs = v; }

    /**
     * 创建一个新的 Builder。
     */
    public static Builder builder() { return new Builder(); }

    /**
     * MemoryRetrievalQuery 的建造者。
     */
    public static class Builder {
        /**
         * 正在构建的 MemoryRetrievalQuery 实例。
         */
        private final MemoryRetrievalQuery q = new MemoryRetrievalQuery();
        /**
         * 设置检索文本。
         */
        public Builder query(String v) { q.query = v; return this; }
        /**
         * 设置最大结果数。
         */
        public Builder maxResults(int v) { q.maxResults = v; return this; }
        /**
         * 设置租户 ID。
         */
        public Builder tenantId(String v) { q.tenantId = v; return this; }
        /**
         * 设置用户 ID。
         */
        public Builder userId(String v) { q.userId = v; return this; }
        /**
         * 设置会话 ID。
         */
        public Builder sessionId(String v) { q.sessionId = v; return this; }
        /**
         * 设置角色过滤。
         */
        public Builder role(String v) { q.role = v; return this; }
        /**
         * 设置标签过滤集合。
         */
        public Builder tags(Set<String> v) { q.tags = v; return this; }
        /**
         * 设置最低重要性分值。
         */
        public Builder minImportance(double v) { q.minImportance = v; return this; }
        /**
         * 设置开始时间戳（毫秒）。
         */
        public Builder fromEpochMs(long v) { q.fromEpochMs = v; return this; }
        /**
         * 设置结束时间戳（毫秒）。
         */
        public Builder toEpochMs(long v) { q.toEpochMs = v; return this; }
        /**
         * 构建并返回 MemoryRetrievalQuery。
         */
        public MemoryRetrievalQuery build() { return q; }
    }
}
