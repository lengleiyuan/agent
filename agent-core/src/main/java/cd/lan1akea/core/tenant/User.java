package cd.lan1akea.core.tenant;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 用户实体。
 */
public class User {

    private final UserId id;
    private final long tenantId;
    private final String username;
    private final String status;
    private final List<Role> roles;
    private final LocalDateTime createdAt;

    public User(UserId id, long tenantId, String username, String status,
                 List<Role> roles, LocalDateTime createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.username = username;
        this.status = status;
        this.roles = roles != null ? Collections.unmodifiableList(roles) : Collections.emptyList();
        this.createdAt = createdAt;
    }

    public UserId getId() { return id; }
    public long getTenantId() { return tenantId; }
    public String getUsername() { return username; }
    public String getStatus() { return status; }
    public List<Role> getRoles() { return roles; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public boolean isActive() { return "ACTIVE".equals(status); }
}
