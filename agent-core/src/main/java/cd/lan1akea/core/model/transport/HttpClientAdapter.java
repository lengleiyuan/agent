package cd.lan1akea.core.model.transport;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * HTTP 客户端适配接口。
 * 对底层 HTTP 客户端（Reactor Netty、OkHttp 等）的抽象。
 * 使 Model 层不直接依赖具体的 HTTP 实现。
 */
public interface HttpClientAdapter {

    /**
     * 发送 POST 请求并获取响应体（字符串）。
     *
     * @param url    请求URL
     * @param headers 请求头
     * @param body    请求体JSON
     * @return Mono&lt;String&gt; 响应体
     */
    Mono<String> post(String url, Map<String, String> headers, String body);

    /**
     * 发送 POST 请求并以 SSE 流方式获取响应。
     *
     * @param url    请求URL
     * @param headers 请求头
     * @param body    请求体JSON
     * @return Flux&lt;String&gt; SSE 事件行流
     */
    Flux<String> postStream(String url, Map<String, String> headers, String body);

    /**
     * 发送 GET 请求。
     *
     * @param url    请求URL
     * @param headers 请求头
     * @return Mono&lt;String&gt; 响应体
     */
    Mono<String> get(String url, Map<String, String> headers);
}
