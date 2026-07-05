package cd.lan1akea.core.tool;

import cd.lan1akea.core.model.ToolSchema;
import cd.lan1akea.core.util.TypeSchemaGenerator;
import cd.lan1akea.core.util.ValidationUtils;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具抽象基类。
 * 提供 Schema 生成、参数校验的默认实现。子类只需声明参数并实现 execute()。
 */
public abstract class ToolBase implements Tool {

    /**
     * 声明的参数列表
     */
    private final List<ToolParam> params = new ArrayList<>();

    /**
     * 复杂参数的完整 JSON Schema（key = 参数名）。
     */
    private final Map<String, Map<String, Object>> complexSchemas = new LinkedHashMap<>();

    /**
     * 声明工具参数。在子类构造方法中调用。
     *
     * @param param 要声明的参数
     */
    protected void declareParam(ToolParam param) {
        params.add(param);
    }

    /**
     * 声明复杂类型参数（POJO / 嵌套对象），自动从 Type 生成 JSON Schema。
     *
     * @param name        参数名称
     * @param description 参数描述
     * @param required    是否必需
     * @param type        参数的 Java 泛型类型（支持 Class、ParameterizedType）
     */
    protected void declareObjectParam(String name, String description, boolean required, Type type) {
        Map<String, Object> schema = TypeSchemaGenerator.generate(type);
        complexSchemas.put(name, schema);
        declareParam(ToolParam.builder(name, "object")
            .description(description).required(required).build());
    }

    /**
     * 声明数组类型参数，自动从元素 Type 生成 items schema。
     *
     * @param name        参数名称
     * @param description 参数描述
     * @param required    是否必需
     * @param elementType 数组元素的 Java 泛型类型
     */
    protected void declareArrayParam(String name, String description, boolean required, Type elementType) {
        Map<String, Object> items = TypeSchemaGenerator.generate(elementType);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("items", items);
        complexSchemas.put(name, schema);
        declareParam(ToolParam.builder(name, "array")
            .description(description).required(required).build());
    }

    /**
     * 快速声明字符串参数。
     *
     * @param name        参数名称
     * @param description 参数描述
     * @param required    是否必需
     */
    protected void declareStringParam(String name, String description, boolean required) {
        declareParam(ToolParam.builder(name, "string")
            .description(description).required(required).build());
    }

    /**
     * 快速声明数字参数。
     *
     * @param name        参数名称
     * @param description 参数描述
     * @param required    是否必需
     */
    protected void declareNumberParam(String name, String description, boolean required) {
        declareParam(ToolParam.builder(name, "number")
            .description(description).required(required).build());
    }

    /**
     * 快速声明布尔参数。
     *
     * @param name        参数名称
     * @param description 参数描述
     * @param required    是否必需
     */
    protected void declareBooleanParam(String name, String description, boolean required) {
        declareParam(ToolParam.builder(name, "boolean")
            .description(description).required(required).build());
    }

    /**
     * 生成工具参数 JSON Schema。
     * 基于声明的参数列表和复杂类型 Schema 构建完整的 parameters 定义。
     *
     * @return 工具参数 Schema
     */
    @Override
    public ToolSchema getParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> requiredFields = new ArrayList<>();

        for (ToolParam param : params) {
            Map<String, Object> propDef;
            Map<String, Object> complexSchema = complexSchemas.get(param.getName());
            if (complexSchema != null) {
                propDef = new LinkedHashMap<>(complexSchema);
            } else {
                propDef = new LinkedHashMap<>();
                propDef.put("type", param.getType());
            }
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
     * 校验必需参数是否存在。
     * 失败时给出明确错误信息，帮助模型自修正。
     *
     * @param callParam 调用参数
     * @throws IllegalArgumentException 缺少必需参数时抛出（含实际传入 key 和期望 key）
     */
    protected void validateParams(ToolCallContext callParam) {
        ValidationUtils.notNull(callParam, "callParam");
        for (ToolParam param : params) {
            if (param.isRequired()) {
                Object value = callParam.get(param.getName());
                if (value == null) {
                    java.util.Set<String> actualKeys = callParam.getArguments().asMap().keySet();
                    String requiredNames = params.stream()
                            .filter(ToolParam::isRequired)
                            .map(ToolParam::getName)
                            .reduce((a, b) -> a + ", " + b).orElse("");
                    String actualStr = actualKeys.isEmpty() ? "（无参数）" : String.join(", ", actualKeys);
                    throw new IllegalArgumentException(
                            "参数名不匹配。你传了 [" + actualStr + "]，但工具 [" + getName()
                            + "] 需要 [" + requiredNames + "]。缺少: " + param.getName());
                }
            }
        }
    }
}
