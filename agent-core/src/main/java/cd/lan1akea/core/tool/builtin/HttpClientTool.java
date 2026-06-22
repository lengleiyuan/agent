package cd.lan1akea.core.tool.builtin;

import cd.lan1akea.core.tool.ToolBase;
import cd.lan1akea.core.tool.ToolCallParam;
import cd.lan1akea.core.tool.ToolResult;
import cd.lan1akea.core.util.JsonUtils;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * HTTP 客户端工具。
 */
public class HttpClientTool extends ToolBase {

    private final HttpClient httpClient;

    public HttpClientTool() {
        this(HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build());
    }

    public HttpClientTool(HttpClient httpClient) {
        this.httpClient = httpClient;
        declareStringParam("url", "请求地址", true);
        declareStringParam("method", "HTTP方法（GET/POST/PUT/DELETE）", false);
        declareStringParam("body", "请求体JSON", false);
        declareStringParam("headers_json", "请求头JSON", false);
    }

    @Override
    public String getName() { return "http_client"; }

    @Override
    public String getDescription() { return "发送 HTTP 请求并获取响应"; }

    @Override
    public Mono<ToolResult> execute(ToolCallParam params) {
        return Mono.fromCallable(() -> {
            validateParams(params);
            String url = params.getString("url");
            String method = params.getString("method");
            if (method == null || method.isEmpty()) method = "GET";
            String body = params.getString("body");
            String headersJson = params.getString("headers_json");

            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30));

                // 请求头
                builder.header("Content-Type", "application/json");
                if (headersJson != null && !headersJson.isEmpty()) {
                    Map<String, Object> headers = JsonUtils.fromJson(headersJson, Map.class);
                    if (headers != null) {
                        headers.forEach((k, v) -> builder.header(k, String.valueOf(v)));
                    }
                }

                // 请求体
                HttpRequest.BodyPublisher bodyPublisher = (body != null && !body.isEmpty())
                    ? HttpRequest.BodyPublishers.ofString(body)
                    : HttpRequest.BodyPublishers.noBody();

                builder.method(method.toUpperCase(), bodyPublisher);
                HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
                return ToolResult.success(
                    "HTTP " + response.statusCode() + "\n" + response.body());
            } catch (Exception e) {
                return ToolResult.failure("HTTP请求失败: " + e.getMessage());
            }
        });
    }
}
