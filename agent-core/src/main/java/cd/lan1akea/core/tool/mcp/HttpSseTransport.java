package cd.lan1akea.core.tool.mcp;

import cd.lan1akea.core.model.transport.ReactorHttpClientAdapter;
import cd.lan1akea.core.util.JsonUtils;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * MCP HTTP+SSE 传输实现。
 * 通过 HTTP POST 发送 JSON-RPC 请求。
 * 支持可选 API Key 认证。
 */
public class HttpSseTransport implements McpTransport {

    /**
     * MCP 服务端点地址。
     */
    private final String endpoint;
    /**
     * 可选的 API 密钥。
     */
    private final String apiKey;
    /**
     * Reactor Netty HTTP 客户端适配器。
     */
    private final ReactorHttpClientAdapter http;
    /**
     * 是否已成功初始化连接。
     */
    private volatile boolean connected;

    /**
     * 使用端点地址创建传输层实例（无 API 密钥）。
     *
     * @param endpoint MCP 服务端点 URL
     */
    public HttpSseTransport(String endpoint) {
        this(endpoint, null);
    }

    /**
     * 使用端点地址和 API 密钥创建传输层实例。
     *
     * @param endpoint MCP 服务端点 URL
     * @param apiKey   可选的 API 密钥
     */
    public HttpSseTransport(String endpoint, String apiKey) {
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.apiKey = apiKey;
        this.http = new ReactorHttpClientAdapter();
    }

    /**
     * 执行 MCP initialize 握手并建立连接。
     */
    @Override
    public Mono<Void> initialize() {
        Map<String, Object> initReq = Map.of(
            "jsonrpc", "2.0",
            "id", 0,
            "method", "initialize",
            "params", Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "HarnessAgent", "version", "1.0")
            )
        );

        return sendRaw(JsonUtils.toCompactJson(initReq))
            .doOnSuccess(r -> connected = true)
            .then();
    }

    /**
     * 发送 JSON-RPC 请求，必要时先初始化连接。
     */
    @Override
    public Mono<String> send(String jsonRpcRequest) {
        if (!connected) {
            return initialize().then(sendRaw(jsonRpcRequest));
        }
        return sendRaw(jsonRpcRequest);
    }

    /**
     * 发送原始 POST 请求体并返回响应。
     *
     * @param body 请求体 JSON 字符串
     * @return 响应体字符串
     */
    private Mono<String> sendRaw(String body) {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        if (apiKey != null && !apiKey.isBlank()) {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        return http.post(endpoint, headers, body);
    }

    /**
     * 返回是否已成功初始化连接。
     */
    @Override
    public boolean isConnected() { return connected; }

    /**
     * 关闭连接并释放资源。
     */
    @Override
    public void close() {
        connected = false;
    }
}
