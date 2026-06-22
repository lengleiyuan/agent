package cd.lan1akea.core.tenant;

/**
 * 当前租户上下文值对象。
 */
public class TenantContext {

    private final long tenantId;
    private final String tenantName;
    private final String userId;

    public TenantContext(long tenantId, String tenantName, String userId) {
        this.tenantId = tenantId;
        this.tenantName = tenantName;
        this.userId = userId;
    }

    public long getTenantId() { return tenantId; }
    public String getTenantName() { return tenantName; }
    public String getUserId() { return userId; }
}
