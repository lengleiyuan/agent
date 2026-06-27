package cd.lan1akea.core.session;

/**
 * 会话状态枚举。
 */
public enum SessionState {

    /**
     * 活跃中
     */
    ACTIVE,

    /**
     * 已暂停
     */
    PAUSED,

    /**
     * 已关闭
     */
    CLOSED,

    /**
     * 已过期
     */
    EXPIRED
}
