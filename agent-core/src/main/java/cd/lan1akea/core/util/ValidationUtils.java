package cd.lan1akea.core.util;

import java.util.Collection;
import java.util.Objects;

/**
 * 参数校验工具类。
 * <p>
 * 在框架内部使用，避免在核心层引入 Spring Validation。
 * 校验失败抛出 IllegalArgumentException。
 * </p>
 */
public final class ValidationUtils {

    private ValidationUtils() {
        // 工具类，禁止实例化
    }

    /**
     * 断言对象非null。
     *
     * @param obj   待校验对象
     * @param name  参数名（用于错误消息）
     * @throws IllegalArgumentException 如果为null
     */
    public static void notNull(Object obj, String name) {
        if (obj == null) {
            throw new IllegalArgumentException(name + " 不能为 null");
        }
    }

    /**
     * 断言字符串非空。
     *
     * @param str  待校验字符串
     * @param name 参数名
     * @throws IllegalArgumentException 如果为空或空白
     */
    public static void notBlank(String str, String name) {
        if (StringUtils.isBlank(str)) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }

    /**
     * 断言集合非空。
     *
     * @param collection 待校验集合
     * @param name       参数名
     * @throws IllegalArgumentException 如果为null或空集合
     */
    public static void notEmpty(Collection<?> collection, String name) {
        if (collection == null || collection.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空集合");
        }
    }

    /**
     * 断言数值为正数（>0）。
     *
     * @param value 待校验数值
     * @param name  参数名
     * @throws IllegalArgumentException 如果 ≤ 0
     */
    public static void positive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " 必须为正数，实际值: " + value);
        }
    }

    /**
     * 断言条件为真。
     *
     * @param condition 条件表达式
     * @param message   错误消息
     * @throws IllegalArgumentException 如果条件为假
     */
    public static void isTrue(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 断言两个对象相等。
     *
     * @param a   对象a
     * @param b   对象b
     * @param name 参数名
     * @throws IllegalArgumentException 如果不相等
     */
    public static void equals(Object a, Object b, String name) {
        if (!Objects.equals(a, b)) {
            throw new IllegalArgumentException(
                name + " 不匹配: " + a + " != " + b);
        }
    }

    /**
     * 断言字符串长度不超过上限。
     *
     * @param str      待校验字符串
     * @param maxLength 最大长度
     * @param name     参数名
     * @throws IllegalArgumentException 如果超长
     */
    public static void maxLength(String str, int maxLength, String name) {
        if (str != null && str.length() > maxLength) {
            throw new IllegalArgumentException(
                name + " 长度不能超过 " + maxLength + "，实际长度: " + str.length());
        }
    }
}
