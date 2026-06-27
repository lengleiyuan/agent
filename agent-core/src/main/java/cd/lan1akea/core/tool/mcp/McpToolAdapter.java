package cd.lan1akea.core.tool.mcp;

import cd.lan1akea.core.model.ToolSchema;
import cd.lan1akea.core.tool.Tool;
import cd.lan1akea.core.tool.ToolCallContext;
import cd.lan1akea.core.tool.ToolResult;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP 工具适配器 — 将 MCP Server 的工具包装为 SDK 的 Tool 接口。
 * 一个适配器绑定一个 MCP 工具。用法：
 *
 * McpClient client = new McpClient(new HttpSseTransport("https://mcp.example.com"));
 * client.connect().block();
 *
 * // 发现工具
 * List<McpToolInfo> tools = client.listTools().block();
 *
 * // 包装为 SDK Tool，注册到 ToolRegistry
 * for (McpToolInfo info : tools) {
 *     toolRegistry.register(new McpToolAdapter(client, info));
 * }
 */
public class McpToolAdapter implements Tool {

    /**
     * MCP 客户端，用于调用工具。
     */
    private final McpClient client;
    /**
     * 工具元数据。
     */
    private final McpToolInfo info;

    /**
     * 创建 MCP 工具适配器。
     *
     * @param client MCP 客户端
     * @param info   工具元信息
     */
    public McpToolAdapter(McpClient client, McpToolInfo info) {
        this.client = client;
        this.info = info;
    }

    /**
     * 返回 MCP 工具名称。
     */
    @Override
    public String getName() {
        return info.getName();
    }

    /**
     * 返回 MCP 工具描述。
     */
    @Override
    public String getDescription() {
        return info.getDescription();
    }

    /**
     * 返回 MCP 工具的输入参数 schema。
     */
    @Override
    public ToolSchema getParameters() {
        Map<String, Object> schema = info.getInputSchema();
        if (schema == null) {
            schema = Map.of("type", "object", "properties", Map.of());
        }
        return new ToolSchema(info.getName(), info.getDescription(), schema);
    }

    /**
     * 执行 MCP 工具并返回结果。
     */
    @Override
    public Mono<ToolResult> execute(ToolCallContext params) {
        return client.callTool(info.getName(), params.getArgumentsMap())
            .map(ToolResult::success)
            .onErrorResume(e -> Mono.just(ToolResult.failure(
                "MCP工具 [" + info.getName() + "] 调用失败: " + e.getMessage())));
    }

    /**
     * 返回 MCP 调用的超时时间（60 秒）。
     */
    @Override
    public long getTimeoutMs() {
        return 60_000; // MCP 调用默认 60s 超时
    }

    /**
     * 返回适配器的字符串表示，包含工具名称。
     */
    @Override
    public String toString() {
        return "McpToolAdapter{name=" + info.getName() + "}";
    }
}
