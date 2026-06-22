package cd.lan1akea.core.tenant;

import cd.lan1akea.core.exception.TenantIsolationException;

/**
 * 租户隔离校验器。
 * <p>
 * 所有数据访问操作前调用此校验器，确保数据隔离。
 * </p>
 */
public class TenantIsolationValidator {

    /**
     * 校验资源归属。
     *
     * @param expectedTenantId 当前租户ID
     * @param actualTenantId   资源所属租户ID
     * @param resourceName     资源名称
     * @throws TenantIsolationException 如果不匹配
     */
    public void validate(long expectedTenantId, long actualTenantId, String resourceName) {
        if (expectedTenantId != actualTenantId) {
            throw new TenantIsolationException(expectedTenantId, actualTenantId, resourceName);
        }
    }
}
