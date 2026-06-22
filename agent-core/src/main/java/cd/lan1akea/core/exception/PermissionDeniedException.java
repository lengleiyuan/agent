package cd.lan1akea.core.exception;

/**
 * 权限拒绝异常。
 */
public class PermissionDeniedException extends AgentException {

    private final String userId;
    private final String resource;
    private final String action;

    public PermissionDeniedException(String userId, String resource, String action) {
        super("PRM_001", "权限不足: 用户=" + userId
            + "，资源=" + resource + "，操作=" + action);
        this.userId = userId;
        this.resource = resource;
        this.action = action;
    }

    /** @return 用户ID */
    public String getUserId() { return userId; }

    /** @return 资源标识 */
    public String getResource() { return resource; }

    /** @return 操作类型 */
    public String getAction() { return action; }
}
