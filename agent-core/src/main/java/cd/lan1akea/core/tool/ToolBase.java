package cd.lan1akea.core.tool;

import cd.lan1akea.core.model.ToolSchema;
import cd.lan1akea.core.util.ValidationUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具抽象基类。
 * <p>
 * 提供 Schema 生成、参数校验的默认实现。子类只需声明参数并实现 execute()。
 * </p>
 */
public abstract class ToolBase implements Tool {

    private final List<ToolParam> params = new ArrayList<>();

    /**
     * 声明工具参数。在子类构造函数中调用。
     */
    protected void declareParam(ToolParam param) {
        params.add(param);
    }

    /**
     * 快速声明字符串参数。
     */
    protected void declareStringParam(String name, String description, boolean required) {
        declareParam(ToolParam.builder(name, "string")
            .description(description).required(required).build());
    }

    /**
     * 快速声明数值参数。
     */
    protected void declareNumberParam(String name, String description, boolean required) {
        declareParam(ToolParam.builder(name, "number")
            .description(description).required(required).build());
    }

    /**
     * 快速声明布尔参数。
     */
    protected void declareBooleanParam(String name, String description, boolean required) {
        declareParam(ToolParam.builder(name, "boolean")
            .description(description).required(required).build());
    }

    @Override
    public ToolSchema getParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> requiredFields = new ArrayList<>();

        for (ToolParam param : params) {
            Map<String, Object> propDef = new LinkedHashMap<>();
            propDef.put("type", param.getType());
            propDef.put("description", param.getDescription());
            if (param.getDefaultValue() != null) {
                propDef.put("default", param.getDefaultValue());
            }
            if (param.getEnumValues() != null && param.getEnumValues().length > 0) {
                propDef.put("enum", param.getEnumValues());
            }
            properties.put(param.getName(), propDef);

            if (param.isRequired()) {
                requiredFields.add(param.getName());
            }
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!requiredFields.isEmpty()) {
            schema.put("required", requiredFields);
        }

        return new ToolSchema(getName(), getDescription(), schema);
    }

    /**
     * 校验调用参数是否满足 Schema 要求。
     *
     * @param callParam 调用参数
     * @throws IllegalArgumentException 如果参数不合法
     */
    protected void validateParams(ToolCallParam callParam) {
        ValidationUtils.notNull(callParam, "callParam");
        for (ToolParam param : params) {
            if (param.isRequired()) {
                Object value = callParam.get(param.getName());
                if (value == null) {
                    throw new IllegalArgumentException(
                        "工具 [" + getName() + "] 缺少必需参数: " + param.getName());
                }
            }
        }
    }
}
