package cd.lan1akea.core.exception;

/**
 * 租户隔离异常，在跨租户数据访问时抛出。
 */
public class TenantIsolationException extends AgentException {

    private final long expectedTenantId;
    private final long actualTenantId;

    public TenantIsolationException(long expectedTenantId, long actualTenantId, String resource) {
        super("TNT_001", "租户隔离违规: 期望租户=" + expectedTenantId
            + "，实际租户=" + actualTenantId + "，资源=" + resource);
        this.expectedTenantId = expectedTenantId;
        this.actualTenantId = actualTenantId;
    }

    /** @return 期望租户ID */
    public long getExpectedTenantId() { return expectedTenantId; }

    /** @return 实际租户ID */
    public long getActualTenantId() { return actualTenantId; }
}
