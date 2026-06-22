package cd.lan1akea.core.tenant;

import java.util.List;

/**
 * 权限评估引擎。
 * <p>
 * 评估 (用户, 资源, 操作) → PermissionDecision。
 * 按权限模式（STRICT/PERMISSIVE）决定默认行为。
 * </p>
 */
public class PermissionEngine {

    private final PermissionMode mode;
    private final List<PermissionRule> rules;

    public PermissionEngine(PermissionMode mode, List<PermissionRule> rules) {
        this.mode = mode;
        this.rules = rules != null ? rules : List.of();
    }

    /**
     * 评估权限。
     *
     * @param user     用户
     * @param resource 资源类型
     * @param action   操作类型
     * @return PermissionDecision
     */
    public PermissionDecision evaluate(User user, ResourceType resource, String action) {
        if (!user.isActive()) {
            return PermissionDecision.deny("用户已被禁用");
        }

        String permissionKey = resource.name().toLowerCase() + ":" + action;

        for (Role role : user.getRoles()) {
            for (Permission perm : role.getPermissions()) {
                if (perm.getPermissionKey().equals(permissionKey)
                    || perm.getPermissionKey().equals("*:*")) {
                    return PermissionDecision.allow();
                }
            }
        }

        // 应用权限规则
        for (PermissionRule rule : rules) {
            if (rule.matches(resource, action)) {
                return rule.getBehavior() == PermissionBehavior.ALLOW
                    ? PermissionDecision.allow()
                    : PermissionDecision.deny("规则限制: " + rule.getDescription());
            }
        }

        // 默认行为
        if (mode == PermissionMode.PERMISSIVE) {
            return PermissionDecision.allow();
        }
        return PermissionDecision.deny("无权限: " + permissionKey);
    }
}
