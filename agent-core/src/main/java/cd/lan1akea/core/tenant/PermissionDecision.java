package cd.lan1akea.core.tenant;

/**
 * 权限决策结果。
 */
public class PermissionDecision {

    private final Decision decision;
    private final String reason;

    public enum Decision { ALLOW, DENY, ASK }

    private PermissionDecision(Decision decision, String reason) {
        this.decision = decision;
        this.reason = reason;
    }

    public static PermissionDecision allow() {
        return new PermissionDecision(Decision.ALLOW, null);
    }

    public static PermissionDecision deny(String reason) {
        return new PermissionDecision(Decision.DENY, reason);
    }

    public static PermissionDecision ask(String reason) {
        return new PermissionDecision(Decision.ASK, reason);
    }

    public boolean isAllowed() { return decision == Decision.ALLOW; }
    public boolean isDenied() { return decision == Decision.DENY; }
    public boolean isAsk() { return decision == Decision.ASK; }
    public Decision getDecision() { return decision; }
    public String getReason() { return reason; }
}
