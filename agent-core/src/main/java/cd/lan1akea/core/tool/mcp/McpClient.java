package cd.lan1akea.core.tool.mcp;

import cd.lan1akea.core.util.JsonUtils;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP JSON-RPC 客户端。
 * <p>
 * 封装与 MCP Server 的 JSON-RPC 2.0 通信，提供工具发现和调用能力。
 * </p>
 */
public class McpClient implements AutoCloseable {

    private final McpTransport transport;
    private final AtomicInteger requestId = new AtomicInteger(1);

    public McpClient(McpTransport transport) {
        this.transport = transport;
    }

    /** 初始化连接 */
    public Mono<Void> connect() {
        return transport.initialize();
    }

    // ========================================================================
    // 工具发现
    // ========================================================================

    /**
     * 列出 MCP Server 提供的所有工具。
     */
    @SuppressWarnings("unchecked")
    public Mono<List<McpToolInfo>> listTools() {
        return rpcCall("tools/list", Map.of())
            .map(response -> {
                List<Map<String, Object>> tools =
                    (List<Map<String, Object>>) response.get("tools");
                if (tools == null) return List.of();
                return tools.stream()
                    .map(McpToolInfo::fromMap)
                    .toList();
            });
    }

    // ========================================================================
    // 工具调用
    // ========================================================================

    /**
     * 调用 MCP 工具。
     *
     * @param toolName  工具名称
     * @param arguments 参数
     * @return 工具返回的文本内容
     */
    @SuppressWarnings("unchecked")
    public Mono<String> callTool(String toolName, Map<String, Object> arguments) {
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("name", toolName);
        params.put("arguments", arguments);

        return rpcCall("tools/call", params)
            .map(response -> {
                List<Map<String, Object>> content =
                    (List<Map<String, Object>>) response.get("content");
                if (content == null || content.isEmpty()) return "";

                StringBuilder sb = new StringBuilder();
                for (Map<String, Object> block : content) {
                    String type = (String) block.get("type");
                    if ("text".equals(type)) {
                        sb.append((String) block.get("text"));
                    } else if ("image".equals(type)) {
                        sb.append("[image: ").append(block.get("data")).append("]");
                    } else if ("resource".equals(type)) {
                        sb.append("[resource: ").append(block.get("uri")).append("]");
                    }
                }
                return sb.toString();
            });
    }

    // ========================================================================
    // 内部
    // ========================================================================

    @SuppressWarnings("unchecked")
    private Mono<Map<String, Object>> rpcCall(String method, Map<String, Object> params) {
        int id = requestId.getAndIncrement();
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.put("params", params);

        return transport.send(JsonUtils.toCompactJson(request))
            .map(responseJson -> {
                Map<String, Object> map = JsonUtils.fromJson(responseJson, Map.class);
                if (map.containsKey("error")) {
                    Map<String, Object> err = (Map<String, Object>) map.get("error");
                    throw new McpException("MCP error: " + err.get("message"));
                }
                return (Map<String, Object>) map.get("result");
            });
    }

    @Override
    public void close() {
        transport.close();
    }
}
