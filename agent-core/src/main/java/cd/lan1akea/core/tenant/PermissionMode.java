package cd.lan1akea.core.tenant;

/**
 * 权限模式枚举。
 */
public enum PermissionMode {

    /** 严格模式：没有显式授权的操作默认拒绝 */
    STRICT,

    /** 宽松模式：没有显式拒绝的操作默认允许 */
    PERMISSIVE
}
