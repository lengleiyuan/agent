package cd.lan1akea.core.util;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Set;

/**
 * 将 LLM 传入的 raw 参数值转换为目标 Java 类型。
 * 统一处理所有类型：基本类型、String、日期、POJO、泛型容器等。
 */
public final class ArgumentConverter {

    private ArgumentConverter() {}

    /** isInstance 安全的类型：值类型，不存在泛型子类型歧义 */
    private static final Set<Class<?>> VALUE_TYPES = Set.of(
        String.class, Integer.class, Long.class, Double.class, Float.class,
        Boolean.class, Short.class, Byte.class, Character.class,
        BigInteger.class, BigDecimal.class
    );

    /**
     * 将 raw 值转换为 targetType 类型。
     *
     * @param raw        原始值（String / Number / Boolean / Map / List）
     * @param targetType 目标类型（支持 Class、ParameterizedType）
     * @return 转换后的对象，raw 为 null 时返回 null
     */
    public static Object convert(Object raw, Type targetType) {
        if (raw == null) return null;

        if (targetType instanceof Class<?> clazz) {
            return convertToClass(raw, clazz);
        }
        if (targetType instanceof ParameterizedType pt) {
            return convertViaJson(raw, pt);
        }
        return raw;
    }

    // ========================================================================
    // internal
    // ========================================================================

    private static Object convertToClass(Object raw, Class<?> clazz) {
        // 仅对值类型做 isInstance 短路，避免 List.class.isInstance(listOfMaps) 这种泛型陷阱
        if (VALUE_TYPES.contains(clazz) && clazz.isInstance(raw)) return raw;

        if (clazz == String.class) return raw.toString();

        // numeric
        if (clazz == int.class || clazz == Integer.class) return toInt(raw);
        if (clazz == long.class || clazz == Long.class) return toLong(raw);
        if (clazz == double.class || clazz == Double.class) return toDouble(raw);
        if (clazz == float.class || clazz == Float.class) return toFloat(raw);
        if (clazz == short.class || clazz == Short.class) return toShort(raw);
        if (clazz == byte.class || clazz == Byte.class) return toByte(raw);
        if (clazz == BigInteger.class) return new BigInteger(raw.toString());
        if (clazz == BigDecimal.class) return new BigDecimal(raw.toString());

        // boolean
        if (clazz == boolean.class || clazz == Boolean.class) return toBoolean(raw);

        // complex → fastjson2
        return convertViaJson(raw, clazz);
    }

    private static Object convertViaJson(Object raw, Type targetType) {
        String json = JsonUtils.toCompactJson(raw);
        return JsonUtils.fromJson(json, targetType);
    }

    private static int toInt(Object raw) {
        if (raw instanceof Number n) return n.intValue();
        return Integer.parseInt(raw.toString());
    }

    private static long toLong(Object raw) {
        if (raw instanceof Number n) return n.longValue();
        return Long.parseLong(raw.toString());
    }

    private static double toDouble(Object raw) {
        if (raw instanceof Number n) return n.doubleValue();
        return Double.parseDouble(raw.toString());
    }

    private static float toFloat(Object raw) {
        if (raw instanceof Number n) return n.floatValue();
        return Float.parseFloat(raw.toString());
    }

    private static boolean toBoolean(Object raw) {
        if (raw instanceof Boolean b) return b;
        return Boolean.parseBoolean(raw.toString());
    }

    private static short toShort(Object raw) {
        if (raw instanceof Number n) return n.shortValue();
        return Short.parseShort(raw.toString());
    }

    private static byte toByte(Object raw) {
        if (raw instanceof Number n) return n.byteValue();
        return Byte.parseByte(raw.toString());
    }
}
