package cd.lan1akea.core.tenant;

import java.util.Collections;
import java.util.List;

/**
 * 角色实体。
 */
public class Role {

    private final String name;
    private final List<Permission> permissions;

    public Role(String name, List<Permission> permissions) {
        this.name = name;
        this.permissions = permissions != null
            ? Collections.unmodifiableList(permissions)
            : Collections.emptyList();
    }

    public String getName() { return name; }
    public List<Permission> getPermissions() { return permissions; }

    /** 内置角色常量 */
    public static final String ADMIN = "ADMIN";
    public static final String DEVELOPER = "DEVELOPER";
    public static final String VIEWER = "VIEWER";
}
