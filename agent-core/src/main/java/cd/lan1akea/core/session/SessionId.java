package cd.lan1akea.core.session;

import java.util.Objects;

/**
 * 会话标识值对象。
 */
public class SessionId {

    private final String value;

    public SessionId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("会话ID不能为空");
        }
        this.value = value;
    }

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
    public String toString() { return value; }
}
