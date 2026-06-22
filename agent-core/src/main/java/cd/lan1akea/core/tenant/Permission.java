package cd.lan1akea.core.tenant;

/**
 * 权限实体。
 */
public class Permission {

    private final String resource;
    private final String action;

    public Permission(String resource, String action) {
        this.resource = resource;
        this.action = action;
    }

    /** @return 权限标识: "resource:action" */
    public String getPermissionKey() {
        return resource + ":" + action;
    }

    public String getResource() { return resource; }
    public String getAction() { return action; }
}
