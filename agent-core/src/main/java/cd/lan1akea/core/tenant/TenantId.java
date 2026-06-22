package cd.lan1akea.core.tenant;

import java.util.Objects;

/**
 * 租户标识值对象。
 */
public class TenantId {

    private final long value;

    public TenantId(long value) {
        if (value <= 0) {
            throw new IllegalArgumentException("租户ID必须为正数");
        }
        this.value = value;
    }

    public long getValue() { return value; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TenantId)) return false;
        return value == ((TenantId) o).value;
    }

    @Override
    public int hashCode() { return Objects.hash(value); }

    @Override
    public String toString() { return String.valueOf(value); }
}
