package cd.lan1akea.core.memory;

/**
 * 记忆检索查询。
 */
public class MemoryRetrievalQuery {

    private final String query;
    private final int maxResults;
    private final Long tenantId;
    private final Long userId;

    public MemoryRetrievalQuery(String query, int maxResults, Long tenantId, Long userId) {
        this.query = query;
        this.maxResults = maxResults;
        this.tenantId = tenantId;
        this.userId = userId;
    }

    public String getQuery() { return query; }
    public int getMaxResults() { return maxResults; }
    public Long getTenantId() { return tenantId; }
    public Long getUserId() { return userId; }
}
