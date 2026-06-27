package cd.lan1akea.core.model;

import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.SystemMessage;
import cd.lan1akea.core.util.JsonUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * 结构化输出工具。
 * 负责 JSON Schema 生成、Schema 指令注入、以及 LLM 输出不符合预期时的重试提醒。
 * 所有结构化输出逻辑集中在此，不在 Agent 中硬编码。
 */
public class StructuredOutputReminder {

    /**
     * 结构化输出验证的最大重试次数
     */
    private static final int MAX_RETRIES = 3;

    /**
     * 注入到消息开头的 Schema 指令模板（可通过系统属性覆盖）
     */
    static final String SCHEMA_INSTRUCTION_TEMPLATE =
        System.getProperty("lan1akea.structured.schemaPrompt",
            "你必须严格按照以下 JSON Schema 格式输出，不要包含任何其他文字。\nSchema:\n{0}");

    /**
     * 将 Schema 指令注入消息列表开头。
     *
     * @param messages    原始消息列表
     * @param outputClass 期望的输出 Java 类型
     * @return 注入后的新消息列表（不修改原列表）
     */
    public static List<Msg> injectSchemaInstruction(List<Msg> messages, Class<?> outputClass) {
        String schema = generateSchema(outputClass);
        String prompt = SCHEMA_INSTRUCTION_TEMPLATE.replace("{0}", schema);
        List<Msg> augmented = new ArrayList<>(messages.size() + 1);
        augmented.add(SystemMessage.of(prompt));
        augmented.addAll(messages);
        return augmented;
    }

    /**
     * 从 Java 类的字段反射生成 JSON Schema。
     * 映射规则：
     * String - "type": "string"
     * int/long/Integer/Long - "type": "integer"
     * double/float/Double/Float - "type": "number"
     * boolean/Boolean - "type": "boolean"
     * List/array - "type": "array"
     * 其他对象 - "type": "object" + 递归
     * 静态和 transient 字段会被跳过。
     */
    public static String generateSchema(Class<?> outputClass) {
        Map<String, Object> schema = buildSchemaNode(outputClass, new HashSet<>());
        return JsonUtils.toJson(schema);
    }

    /**
     * 从 Java 类递归构建 JSON Schema 映射。
     *
     * @param clazz   要反射的 Java 类
     * @param visited 已访问类集合，防止循环
     * @return JSON Schema 节点映射
     */
    private static Map<String, Object> buildSchemaNode(Class<?> clazz, Set<Class<?>> visited) {
        Map<String, Object> node = new LinkedHashMap<>();

        // 基础类型
        if (clazz == String.class || clazz == Character.class || clazz == char.class) {
            node.put("type", "string");
            return node;
        }
        if (clazz == Integer.class || clazz == int.class
            || clazz == Long.class || clazz == long.class
            || clazz == Short.class || clazz == short.class
            || clazz == Byte.class || clazz == byte.class) {
            node.put("type", "integer");
            return node;
        }
        if (clazz == Double.class || clazz == double.class
            || clazz == Float.class || clazz == float.class) {
            node.put("type", "number");
            return node;
        }
        if (clazz == Boolean.class || clazz == boolean.class) {
            node.put("type", "boolean");
            return node;
        }
        if (clazz.isEnum()) {
            node.put("type", "string");
            Object[] constants = clazz.getEnumConstants();
            List<String> values = new ArrayList<>();
            for (Object c : constants) values.add(c.toString());
            node.put("enum", values);
            return node;
        }
        if (List.class.isAssignableFrom(clazz) || clazz.isArray()) {
            node.put("type", "array");
            node.put("items", Map.of("type", "string"));
            return node;
        }
        if (Map.class.isAssignableFrom(clazz)) {
            node.put("type", "object");
            return node;
        }

        // 复杂对象：反射字段
        if (visited.contains(clazz)) return Map.of("type", "object");
        visited.add(clazz);

        node.put("type", "object");
        node.put("description", clazz.getSimpleName());

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (Field field : clazz.getDeclaredFields()) {
            int mod = field.getModifiers();
            if (Modifier.isStatic(mod) || Modifier.isTransient(mod)) continue;

            String name = field.getName();
            Class<?> fieldType = field.getType();
            Map<String, Object> fieldSchema = buildSchemaNode(fieldType, new HashSet<>(visited));
            properties.put(name, fieldSchema);

            // 非 null 判断：原始类型和 @NotNull 标记的字段为 required
            if (fieldType.isPrimitive()) {
                required.add(name);
            }
        }

        node.put("properties", properties);
        if (!required.isEmpty()) node.put("required", required);

        return node;
    }

    /**
     * 构建纠错提醒消息（LLM 输出不符合 Schema 时使用）。
     *
     * @param expectedSchema 预期的 JSON Schema 描述
     * @param actualOutput   实际输出内容
     * @param errorMessage   解析错误信息
     * @param retryCount     当前重试次数
     * @return 提醒消息，如果超过最大重试返回 null
     */
    public static Msg buildReminder(String expectedSchema, String actualOutput,
                                     String errorMessage, int retryCount) {
        if (retryCount >= MAX_RETRIES) {
            return null;
        }

        String reminder = "你的上一轮输出不符合预期的 JSON Schema。请严格按照以下格式重新输出。\n"
            + "预期格式: " + expectedSchema + "\n"
            + "错误: " + errorMessage + "\n"
            + "这是第 " + (retryCount + 1) + " 次重试，最多 " + MAX_RETRIES + " 次。";

        return SystemMessage.of(reminder);
    }
}
