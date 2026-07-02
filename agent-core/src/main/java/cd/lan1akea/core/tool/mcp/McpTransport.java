package cd.lan1akea.core.tool.mcp;

import reactor.core.publisher.Mono;

/**
 * MCP 传输层接口。
 * 封装与 MCP Server 的 JSON-RPC 2.0 通信。
 * 业务方可实现自己的传输层（HTTP+SSE / stdio / WebSocket）。
 */
public interface McpTransport extends AutoCloseable {

    /**
     * 发送 JSON-RPC 请求并返回响应。
     *
     * @param jsonRpcRequest JSON-RPC 请求体 JSON 字符串
     * @return Mono<String> 响应体 JSON 字符串
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

    /**
     * 关闭连接并释放底层资源。
     */
    @Override
    void close();

    /**
     * 异步关闭连接并释放资源。
     *
     * @return 关闭完成的 Mono
     */
    default Mono<Void> closeAsync() {
        try {
            close();
            return Mono.empty();
        } catch (Exception e) {
            return Mono.error(e);
        }
    }
}
