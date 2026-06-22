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
 * <p>
 * 基于 Reactor Netty 实现 HttpClientAdapter。
 * </p>
 */
public class ReactorHttpClientAdapter implements HttpClientAdapter {

    private final HttpClient httpClient;

    public ReactorHttpClientAdapter() {
        this(Duration.ofSeconds(60));
    }

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
