package cd.lan1akea.core.model.transport;

import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Reactor Netty HTTP 客户端适配器。
 * 基于 Reactor Netty 实现 HttpClientAdapter。
 */
public class ReactorHttpClientAdapter implements HttpClientAdapter {

    /**
     * Reactor Netty HttpClient 实例。
     */
    private final HttpClient httpClient;

    /**
     * 使用默认 60 秒超时创建适配器。
     */
    public ReactorHttpClientAdapter() {
        this(Duration.ofSeconds(60));
    }

    /**
     * 使用指定超时时间创建适配器。
     *
     * @param timeout 请求超时时间
     */
    public ReactorHttpClientAdapter(Duration timeout) {
        ConnectionProvider provider = ConnectionProvider.builder("agent-http")
            .maxConnections(200)
            .pendingAcquireTimeout(Duration.ofSeconds(30))
            .build();

        this.httpClient = HttpClient.create(provider)
            .doOnConnected(conn ->
                conn.addHandlerLast(new ReadTimeoutHandler(timeout.toMillis(), TimeUnit.MILLISECONDS))
                    .addHandlerLast(new WriteTimeoutHandler(timeout.toMillis(), TimeUnit.MILLISECONDS)))
            .responseTimeout(timeout);
    }

    @Override
    public Mono<String> post(String url, Map<String, String> headers, String body) {
        return httpClient
            .headers(h -> {
                h.add("Content-Type", "application/json");
                if (headers != null) {
                    headers.forEach(h::add);
                }
            })
            .post()
            .uri(url)
            .send(Mono.just(io.netty.buffer.Unpooled.wrappedBuffer(body.getBytes())))
            .responseSingle((response, bytes) ->
                bytes.asString()
                    .switchIfEmpty(Mono.just(""))
                    .flatMap(bodyStr -> {
                        int status = response.status().code();
                        if (status >= 400) {
                            return Mono.error(new RuntimeException(
                                "HTTP " + status + ": " + bodyStr));
                        }
                        return Mono.just(bodyStr);
                    }));
    }

    @Override
    public Flux<String> postStream(String url, Map<String, String> headers, String body) {
        return httpClient
            .headers(h -> {
                h.add("Content-Type", "application/json");
                h.add("Accept", "text/event-stream");
                if (headers != null) {
                    headers.forEach(h::add);
                }
            })
            .post()
            .uri(url)
            .send(Mono.just(io.netty.buffer.Unpooled.wrappedBuffer(body.getBytes())))
            .responseContent()
            .asString()
            .flatMap(chunk -> {
                // 按 \n 拆分行，处理多个 SSE 事件合并到一个 TCP 包的情况
                String[] lines = chunk.split("\n");
                return Flux.fromArray(lines);
            })
            .filter(line -> !line.isEmpty());
    }

    @Override
    public Mono<String> get(String url, Map<String, String> headers) {
        return httpClient
            .headers(h -> {
                if (headers != null) {
                    headers.forEach(h::add);
                }
            })
            .get()
            .uri(url)
            .responseSingle((response, bytes) ->
                bytes.asString()
                    .switchIfEmpty(Mono.just(""))
                    .flatMap(bodyStr -> {
                        int status = response.status().code();
                        if (status >= 400) {
                            return Mono.error(new RuntimeException(
                                "HTTP " + status + ": " + bodyStr));
                        }
                        return Mono.just(bodyStr);
                    }));
    }
}
