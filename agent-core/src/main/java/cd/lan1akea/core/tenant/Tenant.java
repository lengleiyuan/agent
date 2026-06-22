package cd.lan1akea.core.tenant;

import java.time.LocalDateTime;

/**
 * 租户实体。
 */
public class Tenant {

    private final TenantId id;
    private final String name;
    private final String status;
    private final String quotaJson;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    public Tenant(TenantId id, String name, String status,
                   String quotaJson, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.name = name;
        this.status = status;
        this.quotaJson = quotaJson;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public TenantId getId() { return id; }
    public String getName() { return name; }
    public String getStatus() { return status; }
    public String getQuotaJson() { return quotaJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public boolean isActive() { return "ACTIVE".equals(status); }
}
