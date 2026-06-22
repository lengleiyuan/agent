package cd.lan1akea.core.message;

/**
 * 消息角色枚举。
 */
public enum MsgRole {

    /** 系统消息，用于设定 Agent 行为和上下文 */
    SYSTEM("system"),

    /** 用户消息 */
    USER("user"),

    /** 助手（AI）消息 */
    ASSISTANT("assistant"),

    /** 工具返回结果消息 */
    TOOL("tool");

    private final String value;

    MsgRole(String value) {
        this.value = value;
    }

    /** @return 角色字符串值（与 LLM API 对齐） */
    public String getValue() {
        return value;
    }

    /**
     * 根据字符串值获取角色枚举。
     *
     * @param value 字符串值
     * @return 对应角色枚举
     * @throws IllegalArgumentException 如果值无效
     */
    public static MsgRole fromValue(String value) {
        for (MsgRole role : values()) {
            if (role.value.equalsIgnoreCase(value)) {
                return role;
            }
        }
        throw new IllegalArgumentException("未知的消息角色: " + value);
    }
}
