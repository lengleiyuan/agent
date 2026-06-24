package cd.lan1akea.core.tool.mcp;

import cd.lan1akea.core.model.transport.ReactorHttpClientAdapter;
import cd.lan1akea.core.util.JsonUtils;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * MCP HTTP+SSE 传输实现。
 * <p>
 * 通过 HTTP POST 发送 JSON-RPC 请求。
 * 支持可选 API Key 认证。
 * </p>
 */
public class HttpSseTransport implements McpTransport {

    private final String endpoint;
    private final String apiKey;
    private final ReactorHttpClientAdapter http;
    private volatile boolean connected;

    public HttpSseTransport(String endpoint) {
        this(endpoint, null);
    }

    public HttpSseTransport(String endpoint, String apiKey) {
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.apiKey = apiKey;
        this.http = new ReactorHttpClientAdapter();
    }

    @Override
    public Mono<Void> initialize() {
        // MCP initialize 握手
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

    @Override
    public Mono<String> send(String jsonRpcRequest) {
        if (!connected) {
            return initialize().then(sendRaw(jsonRpcRequest));
        }
        return sendRaw(jsonRpcRequest);
    }

    private Mono<String> sendRaw(String body) {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        if (apiKey != null && !apiKey.isBlank()) {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        return http.post(endpoint, headers, body);
    }

    @Override
    public boolean isConnected() { return connected; }

    @Override
    public void close() {
        connected = false;
    }
}
