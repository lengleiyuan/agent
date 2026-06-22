package cd.lan1akea.core.memory;

/**
 * 带摘要限制的检索查询。
 */
public class LimitedMemoryRetrievalQuery extends MemoryRetrievalQuery {

    private final int maxSummaryLength;

    public LimitedMemoryRetrievalQuery(String query, int maxResults, Long tenantId,
                                        Long userId, int maxSummaryLength) {
        super(query, maxResults, tenantId, userId);
        this.maxSummaryLength = maxSummaryLength;
    }

    public int getMaxSummaryLength() { return maxSummaryLength; }
}
