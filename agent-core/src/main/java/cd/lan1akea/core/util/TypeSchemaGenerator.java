package cd.lan1akea.core.util;

import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 从 Java {@link Type} 递归生成 JSON Schema Map。
 * 用于 Tool 参数 Schema 的自动推断，支持嵌套 POJO、泛型 List/Set、Map、枚举、数组等。
 */
public final class TypeSchemaGenerator {

    private TypeSchemaGenerator() {}

    private static final Set<Class<?>> BASIC_JSON_TYPES = Set.of(
        String.class, char.class, Character.class,
        boolean.class, Boolean.class,
        byte.class, Byte.class, short.class, Short.class,
        int.class, Integer.class, long.class, Long.class,
        BigInteger.class,
        float.class, Float.class, double.class, Double.class,
        BigDecimal.class, Number.class
    );

    /**
     * 从 Type 生成 JSON Schema Map。
     * 使用 processing 集合做循环引用检测，遇循环返回 {"type":"object"} 占位。
     */
    public static Map<String, Object> generate(Type type) {
        return doGenerate(type, new IdentityHashMap<>());
    }

    // ========================================================================
    // internal
    // ========================================================================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> doGenerate(Type type, Map<Type, Boolean> processing) {
        if (type == null) {
            return objType();
        }

        // circular reference guard
        if (processing.containsKey(type)) {
            return objType();
        }

        if (type instanceof Class<?> clazz) {
            return generateClass(clazz, processing);
        }

        if (type instanceof ParameterizedType pt) {
            return generateParameterized(pt, processing);
        }

        if (type instanceof GenericArrayType gat) {
            return arraySchema(doGenerate(gat.getGenericComponentType(), processing));
        }

        // WildcardType / TypeVariable — fallback
        return objType();
    }

    private static Map<String, Object> generateClass(Class<?> clazz, Map<Type, Boolean> processing) {
        // basic → simple type
        String jsonType = basicJsonType(clazz);
        if (jsonType != null) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("type", jsonType);
            return s;
        }

        // array
        if (clazz.isArray()) {
            return arraySchema(doGenerate(clazz.getComponentType(), processing));
        }

        // enum
        if (clazz.isEnum()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("type", "string");
            Object[] constants = clazz.getEnumConstants();
            List<String> vals = new ArrayList<>(constants.length);
            for (Object c : constants) vals.add(c.toString());
            s.put("enum", vals);
            return s;
        }

        // POJO
        return generateObject(clazz, processing);
    }

    private static Map<String, Object> generateObject(Class<?> clazz, Map<Type, Boolean> processing) {
        processing.put(clazz, Boolean.TRUE);
        try {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");

            Map<String, Object> properties = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();

            for (Field field : getAllFields(clazz)) {
                int mod = field.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isTransient(mod)) {
                    continue;
                }

                String name = field.getName();
                Type fieldType = field.getGenericType();
                Map<String, Object> fieldSchema = doGenerate(fieldType, processing);
                fieldSchema.putIfAbsent("description", name);
                properties.put(name, fieldSchema);

                if (field.getType().isPrimitive()) {
                    required.add(name);
                }
            }

            schema.put("properties", properties);
            if (!required.isEmpty()) {
                schema.put("required", required);
            }
            return schema;
        } finally {
            processing.remove(clazz);
        }
    }

    private static Map<String, Object> generateParameterized(ParameterizedType pt, Map<Type, Boolean> processing) {
        Type rawType = pt.getRawType();
        Type[] typeArgs = pt.getActualTypeArguments();

        // Collection / List / Set → array
        if (isAssignableTo(rawType, Collection.class)) {
            Type elementType = typeArgs.length > 0 ? typeArgs[0] : Object.class;
            return arraySchema(doGenerate(elementType, processing));
        }

        // Map<K,V> → object (JSON keys always string)
        if (isAssignableTo(rawType, Map.class)) {
            return objType();
        }

        // Optional<T> → unwrap
        if (isAssignableTo(rawType, Optional.class) && typeArgs.length > 0) {
            return doGenerate(typeArgs[0], processing);
        }

        // fallback → treat raw type as object
        if (rawType instanceof Class<?> clazz) {
            return generateObject(clazz, processing);
        }
        return objType();
    }

    // ========================================================================
    // helpers
    // ========================================================================

    private static Map<String, Object> arraySchema(Map<String, Object> items) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "array");
        s.put("items", items);
        return s;
    }

    private static Map<String, Object> objType() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        return s;
    }

    /**
     * 收集类及其父类的所有非静态非 transient 字段。
     */
    private static List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field f : current.getDeclaredFields()) {
                fields.add(f);
            }
            current = current.getSuperclass();
        }
        return fields;
    }

    /**
     * 基本 Java 类型 → JSON Schema type，不匹配返回 null。
     */
    private static String basicJsonType(Class<?> clazz) {
        if (BASIC_JSON_TYPES.contains(clazz)) {
            if (clazz == String.class || clazz == char.class || clazz == Character.class)
                return "string";
            if (clazz == boolean.class || clazz == Boolean.class)
                return "boolean";
            if (clazz == byte.class || clazz == Byte.class
                || clazz == short.class || clazz == Short.class
                || clazz == int.class || clazz == Integer.class
                || clazz == long.class || clazz == Long.class
                || clazz == BigInteger.class)
                return "integer";
            // float/double/BigDecimal/Number
            return "number";
        }
        // date/time → string
        if (Temporal.class.isAssignableFrom(clazz)
            || Date.class.isAssignableFrom(clazz))
            return "string";
        return null;
    }

    private static boolean isAssignableTo(Type rawType, Class<?> target) {
        if (rawType == target) return true;
        if (rawType instanceof Class<?> clazz) {
            return target.isAssignableFrom(clazz);
        }
        return false;
    }
}
