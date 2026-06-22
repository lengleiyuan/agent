package cd.lan1akea.core.tool;

import cd.lan1akea.core.model.ToolSchema;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 工具参数校验器。
 * <p>
 * 根据工具声明的 JSON Schema 校验调用参数是否合法。
 * </p>
 */
@SuppressWarnings("unchecked")
public class ToolValidator {

    /**
     * 校验工具调用参数。
     *
     * @param schema    工具 Schema
     * @param callParam 实际调用参数
     * @throws IllegalArgumentException 如果参数不合法
     */
    public void validate(ToolSchema schema, ToolCallParam callParam) {
        Map<String, Object> paramsSchema = schema.getParametersSchema();
        Map<String, Object> properties = (Map<String, Object>) paramsSchema.get("properties");
        if (properties == null) {
            return; // 无参数 Schema，跳过校验
        }

        List<String> required = (List<String>) paramsSchema.get("required");

        // 校验必填参数
        if (required != null) {
            for (String field : required) {
                if (callParam.get(field) == null) {
                    throw new IllegalArgumentException(
                        "工具 [" + schema.getName() + "] 缺少必填参数: " + field);
                }
            }
        }

        // 校验参数类型（简单的类型检查）
        Map<String, Object> args = callParam.getArguments();
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            Map<String, Object> propDef = (Map<String, Object>) properties.get(key);
            if (propDef != null) {
                String expectedType = (String) propDef.get("type");
                if (expectedType != null && !matchesType(value, expectedType)) {
                    throw new IllegalArgumentException(
                        "工具 [" + schema.getName() + "] 参数 " + key
                            + " 类型不匹配: 期望 " + expectedType
                            + "，实际 " + value.getClass().getSimpleName());
                }
            }
        }
    }

    private boolean matchesType(Object value, String expectedType) {
        if (value == null) return true;
        switch (expectedType) {
            case "string":  return value instanceof String || value instanceof CharSequence;
            case "number":  return value instanceof Number;
            case "boolean": return value instanceof Boolean;
            case "array":   return value instanceof Collection || value.getClass().isArray();
            case "object":  return value instanceof Map;
            default:        return true;
        }
    }
}
