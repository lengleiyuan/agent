package cd.lan1akea.core.session;

import java.util.Objects;

/**
 * 会话标识值对象。
 */
public class SessionId {

    /**
     * 会话 ID 字符串值
     */
    private final String value;

    /**
     * 创建会话 ID。
     *
     * @param value 会话 ID 字符串
     * @throws IllegalArgumentException 如果值为 null 或空白
     */
    public SessionId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Session ID must not be empty");
        }
        this.value = value;
    }

    /**
     * @return 会话 ID
     */
    public String getValue() { return value; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SessionId)) return false;
        return value.equals(((SessionId) o).value);
    }

    @Override
    public int hashCode() { return Objects.hash(value); }

    @Override
    /**
     * 返回会话 ID 值。
     */
    public String toString() { return value; }
}
