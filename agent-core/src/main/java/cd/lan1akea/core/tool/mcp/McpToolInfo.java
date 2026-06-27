package cd.lan1akea.core.tool.mcp;

import java.util.Map;

/**
 * MCP 工具元数据（对应 MCP tools/list 返回的单条记录）。
 */
public class McpToolInfo {

    /**
     * 工具名称。
     */
    private final String name;
    /**
     * 工具描述。
     */
    private final String description;
    /**
     * 工具的 JSON Schema 输入参数定义。
     */
    private final Map<String, Object> inputSchema;

    /**
     * 创建 MCP 工具元数据。
     *
     * @param name        工具名称
     * @param description 工具描述
     * @param inputSchema 输入参数 schema
     */
    public McpToolInfo(String name, String description, Map<String, Object> inputSchema) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
    }

    /**
     * 从 tools/list 返回的 Map 构造 McpToolInfo。
     *
     * @param map 工具元数据字典
     * @return 解析后的 McpToolInfo
     */
    @SuppressWarnings("unchecked")
    public static McpToolInfo fromMap(Map<String, Object> map) {
        return new McpToolInfo(
            (String) map.get("name"),
            (String) map.getOrDefault("description", ""),
            (Map<String, Object>) map.get("inputSchema")
        );
    }

    /**
     * 返回工具名称。
     */
    public String getName() { return name; }
    /**
     * 返回工具描述。
     */
    public String getDescription() { return description; }
    /**
     * 返回输入参数的 JSON Schema。
     */
    public Map<String, Object> getInputSchema() { return inputSchema; }
}
