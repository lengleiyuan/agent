package cd.lan1akea.core.tenant;

import java.util.Objects;

/**
 * 用户标识值对象。
 */
public class UserId {

    private final long value;

    public UserId(long value) {
        this.value = value;
    }

    public long getValue() { return value; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserId)) return false;
        return value == ((UserId) o).value;
    }

    @Override
    public int hashCode() { return Objects.hash(value); }

    @Override
    public String toString() { return String.valueOf(value); }
}
