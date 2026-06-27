package cd.lan1akea.core.message;

/**
 * 消息角色枚举。
 */
public enum MsgRole {

    /**
     * 系统消息，用于设定 Agent 行为和上下文
     */
    SYSTEM("system"),

    /**
     * 用户消息
     */
    USER("user"),

    /**
     * 助手（AI）消息
     */
    ASSISTANT("assistant"),

    /**
     * 工具返回结果消息
     */
    TOOL("tool");

    /**
     * 角色字符串值。
     */
    private final String value;

    /**
     * 创建带有指定值的消息角色。
     *
     * @param value 角色字符串值
     */
    MsgRole(String value) {
        this.value = value;
    }

    /**
     * @return 角色字符串值（与 LLM API 对齐）
     */
    public String getValue() {
        return value;
    }

    /**
     * 从字符串值解析消息角色。
     *
     * @param value 字符串值
     * @return 匹配的消息角色
     * @throws IllegalArgumentException 未找到匹配角色时抛出
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
