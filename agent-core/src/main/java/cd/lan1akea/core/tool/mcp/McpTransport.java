package cd.lan1akea.core.tool.mcp;

import reactor.core.publisher.Mono;

/**
 * MCP 传输层接口。
 * <p>
 * 封装与 MCP Server 的 JSON-RPC 2.0 通信。
 * 业务方可实现自己的传输层（HTTP+SSE / stdio / WebSocket）。
 * </p>
 */
public interface McpTransport extends AutoCloseable {

    /**
     * 发送 JSON-RPC 请求并返回响应。
     *
     * @param jsonRpcRequest JSON-RPC 请求体 JSON 字符串
     * @return Mono&lt;String&gt; 响应体 JSON 字符串
     */
    Mono<String> send(String jsonRpcRequest);

    /**
     * 初始化连接（握手/协商）。
     */
    Mono<Void> initialize();

    /**
     * 是否已连接。
     */
    boolean isConnected();

    @Override
    void close();
}
