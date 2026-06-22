package cd.lan1akea.core.tool;

import cd.lan1akea.core.model.ToolSchema;

import java.util.List;
import java.util.stream.Collectors;

/**
 * JSON Schema 模块。
 * <p>
 * 聚合多个 ToolSchema，生成符合 OpenAI function calling 格式的工具列表。
 * </p>
 */
public class ToolSchemaModule {

    private final List<Tool> tools;

    public ToolSchemaModule(List<Tool> tools) {
        this.tools = tools;
    }

    /**
     * 生成 OpenAI 兼容的 functions 数组（JSON 字符串）。
     *
     * @return tools JSON 数组字符串
     */
    public String toFunctionsJson() {
        List<String> functionJsons = tools.stream()
            .map(tool -> {
                ToolSchema schema = tool.getParameters();
                return "{\"type\":\"function\",\"function\":{"
                    + "\"name\":\"" + escapeJson(schema.getName()) + "\","
                    + "\"description\":\"" + escapeJson(schema.getDescription()) + "\","
                    + "\"parameters\":" + cd.lan1akea.core.util.JsonUtils.toCompactJson(schema.getParametersSchema())
                    + "}}";
            })
            .collect(Collectors.toList());
        return "[" + String.join(",", functionJsons) + "]";
    }

    /** @return 工具数量 */
    public int size() { return tools.size(); }

    private String escapeJson(String str) {
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
}
