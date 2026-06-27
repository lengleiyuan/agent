package cd.lan1akea.core.model;

import java.util.Map;

/**
 * 工具 Schema 定义。
 * 描述工具的接口，用于传递给 LLM 的 function calling。
 * 包含名称、描述、参数 JSON Schema。
 */
public class ToolSchema {

    /**
     * 工具名称（对 LLM 可见）
     */
    private final String name;

    /**
     * 工具描述
     */
    private final String description;

    /**
     * 参数 JSON Schema
     */
    private final Map<String, Object> parametersSchema;

    /**
     * 创建工具 Schema。
     *
     * @param name             工具名称
     * @param description      工具描述
     * @param parametersSchema 参数 JSON Schema
     */
    public ToolSchema(String name, String description, Map<String, Object> parametersSchema) {
        this.name = name;
        this.description = description;
        this.parametersSchema = parametersSchema;
    }

    /**
     * @return 工具名称
     */
    public String getName() { return name; }

    /**
     * @return 工具描述
     */
    public String getDescription() { return description; }

    /**
     * @return 参数 JSON Schema
     */
    public Map<String, Object> getParametersSchema() { return parametersSchema; }
}
