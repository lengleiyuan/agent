package cd.lan1akea.core.tenant;

/**
 * 权限规则。
 */
public class PermissionRule {

    private final ResourceType resource;
    private final String actionPattern;
    private final PermissionBehavior behavior;
    private final String description;

    public PermissionRule(ResourceType resource, String actionPattern,
                           PermissionBehavior behavior, String description) {
        this.resource = resource;
        this.actionPattern = actionPattern;
        this.behavior = behavior;
        this.description = description;
    }

    /** 判断是否匹配此规则 */
    public boolean matches(ResourceType resource, String action) {
        return this.resource == resource
            && (actionPattern.equals("*") || actionPattern.equals(action));
    }

    public PermissionBehavior getBehavior() { return behavior; }
    public String getDescription() { return description; }
}
