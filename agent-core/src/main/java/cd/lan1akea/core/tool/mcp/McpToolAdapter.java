package cd.lan1akea.core.tool.mcp;

import cd.lan1akea.core.tool.Tool;
import cd.lan1akea.core.tool.ToolCallParam;
import cd.lan1akea.core.tool.ToolResult;
import cd.lan1akea.core.model.ToolSchema;
import reactor.core.publisher.Mono;

/**
 * MCP 工具适配器（预留）。
 * <p>
 * 将 MCP (Model Context Protocol) 服务暴露的工具适配为框架 Tool 接口。
 * 未来对接 MCP SDK 后填充具体实现。
 * </p>
 */
public class McpToolAdapter implements Tool {

    private final String name;
    private final String description;
    private final ToolSchema schema;
    private final String mcpServerName;

    public McpToolAdapter(String name, String description,
                           ToolSchema schema, String mcpServerName) {
        this.name = name;
        this.description = description;
        this.schema = schema;
        this.mcpServerName = mcpServerName;
    }

    @Override
    public String getName() { return name; }

    @Override
    public String getDescription() {
        return description + " (MCP: " + mcpServerName + ")";
    }

    @Override
    public ToolSchema getParameters() { return schema; }

    @Override
    public Mono<ToolResult> execute(ToolCallParam params) {
        // TODO: MCP 协议接入后实现
        // 1. 连接 MCP Server
        // 2. 调用 tools/call
        // 3. 返回结果
        return Mono.just(ToolResult.failure(
            "MCP 工具 [" + name + "] 尚未接入，请等待 MCP 支持"));
    }

    /** @return MCP 服务器名称 */
    public String getMcpServerName() { return mcpServerName; }

    @Override
    public long getTimeoutMs() {
        return 60000; // MCP 调用超时1分钟
    }
}
