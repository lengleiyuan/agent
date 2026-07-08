package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.model.ChatStreamChunk;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 会话级串行化门控。
 * 同一 session 的请求 FIFO 排队，不同 session 并发。
 */
public interface SessionGate {

    /**
     * 串行化 Mono 执行。sessionId 为 null 时不排队直接执行。
     *
     * @param sessionId 会话标识（null 时不排队直接执行）
     * @param work      要串行化的操作
     * @param <T>       结果类型
     * @return 串行执行后的 Mono
     */
    <T> Mono<T> enqueue(String sessionId, Mono<T> work);

    /**
     * 串行化 Flux 执行。sessionId 为 null 时不排队直接执行。
     *
     * @param sessionId 会话标识（null 时不排队直接执行）
     * @param work      要串行化的流式操作
     * @return 串行执行后的 Flux
     */
    Flux<ChatStreamChunk> enqueueStream(String sessionId, Flux<ChatStreamChunk> work);
}
