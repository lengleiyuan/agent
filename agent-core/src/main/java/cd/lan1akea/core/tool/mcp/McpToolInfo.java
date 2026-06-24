package cd.lan1akea.core.tool.mcp;

import java.util.Map;

/**
 * MCP 工具元数据（对应 MCP tools/list 返回的单条记录）。
 */
public class McpToolInfo {

    private final String name;
    private final String description;
    private final Map<String, Object> inputSchema;

    public McpToolInfo(String name, String description, Map<String, Object> inputSchema) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
    }

    @SuppressWarnings("unchecked")
    public static McpToolInfo fromMap(Map<String, Object> map) {
        return new McpToolInfo(
            (String) map.get("name"),
            (String) map.getOrDefault("description", ""),
            (Map<String, Object>) map.get("inputSchema")
        );
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public Map<String, Object> getInputSchema() { return inputSchema; }
}
