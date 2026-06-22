package cd.lan1akea.core.tool;

/**
 * 工具组作用域。
 * <p>
 * 控制工具在不同级别的可见性。
 * </p>
 */
public enum ToolGroupScope {

    /** 全局可见（所有租户共享） */
    GLOBAL,

    /** 租户级可见（同一租户内所有用户共享） */
    TENANT,

    /** 用户级可见（仅当前用户可见） */
    USER,

    /** 会话级可见（仅当前会话可见） */
    SESSION
}
