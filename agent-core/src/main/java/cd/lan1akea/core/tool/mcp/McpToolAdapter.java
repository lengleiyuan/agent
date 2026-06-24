package cd.lan1akea.core.tool.mcp;

import cd.lan1akea.core.model.ToolSchema;
import cd.lan1akea.core.tool.Tool;
import cd.lan1akea.core.tool.ToolCallParam;
import cd.lan1akea.core.tool.ToolResult;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP 工具适配器 — 将 MCP Server 的工具包装为 SDK 的 {@link Tool} 接口。
 * <p>
 * 一个适配器绑定一个 MCP 工具。用法：
 * <pre>
 * McpClient client = new McpClient(new HttpSseTransport("https://mcp.example.com"));
 * client.connect().block();
 *
 * // 发现工具
 * List&lt;McpToolInfo&gt; tools = client.listTools().block();
 *
 * // 包装为 SDK Tool，注册到 ToolRegistry
 * for (McpToolInfo info : tools) {
 *     toolRegistry.register(new McpToolAdapter(client, info));
 * }
 * </pre>
 * </p>
 */
public class McpToolAdapter implements Tool {

    private final McpClient client;
    private final McpToolInfo info;

    public McpToolAdapter(McpClient client, McpToolInfo info) {
        this.client = client;
        this.info = info;
    }

    @Override
    public String getName() {
        return info.getName();
    }

    @Override
    public String getDescription() {
        return info.getDescription();
    }

    @Override
    public ToolSchema getParameters() {
        Map<String, Object> schema = info.getInputSchema();
        if (schema == null) {
            schema = Map.of("type", "object", "properties", Map.of());
        }
        return new ToolSchema(info.getName(), info.getDescription(), schema);
    }

    @Override
    public Mono<ToolResult> execute(ToolCallParam params) {
        return client.callTool(info.getName(), params.getArguments())
            .map(ToolResult::success)
            .onErrorResume(e -> Mono.just(ToolResult.failure(
                "MCP工具 [" + info.getName() + "] 调用失败: " + e.getMessage())));
    }

    @Override
    public long getTimeoutMs() {
        return 60_000; // MCP 调用默认 60s 超时
    }

    @Override
    public String toString() {
        return "McpToolAdapter{name=" + info.getName() + "}";
    }
}
