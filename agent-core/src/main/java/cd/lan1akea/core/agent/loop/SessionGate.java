package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.model.ChatStreamChunk;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级串行化门控。
 * 同一 session 的请求 FIFO 排队，不同 session 并发。
 * 基于 ConcurrentHashMap.put（原子替换）+ Sinks.One（异步信号）。
 *
 * <p>当多个请求同时到达同一个会话时，通过门控机制确保前一个请求完成后
 * 才启动下一个请求，避免并发导致的竞态条件。
 */
public class SessionGate {

    /**
     * 会话门控映射表。
     * key 为 sessionId，value 为前一个请求释放的信号量。
     * 使用 ConcurrentHashMap 保证线程安全，同一 session 的 put 操作为原子替换。
     */
    private final ConcurrentHashMap<String, Sinks.One<Void>> gates = new ConcurrentHashMap<>();

    /**
     * 串行化 Mono 执行。sessionId 为 null 时不排队。
     *
     * <p>如果已有同会话的请求正在执行，当前请求会等待前一个完成后再开始。
     * 使用 {@link Sinks.One} 作为信号量，前一个请求的 doFinally 中发出完成信号。
     *
     * @param sessionId 会话标识（null 时不排队直接执行）
     * @param work      要串行化的操作
     * @param <T>       结果类型
     * @return 串行执行后的 Mono
     */
    public <T> Mono<T> enqueue(String sessionId, Mono<T> work) {
        if (sessionId == null) return work;
        return Mono.defer(() -> {
            Sinks.One<Void> myGate = Sinks.one();
            Sinks.One<Void> prevGate = gates.put(sessionId, myGate);
            Mono<T> execution = prevGate != null
                    ? prevGate.asMono().then(work)
                    : Mono.defer(() -> work);
            return execution.doFinally(s -> myGate.tryEmitEmpty());
        });
    }

    /**
     * 串行化 Flux 执行。sessionId 为 null 时不排队。
     *
     * <p>与 {@link #enqueue(String, Mono)} 类似，但适用于流式返回的场景。
     * 使用 {@link Sinks.One} 作为信号量，前一个请求的 doFinally 中发出完成信号。
     *
     * @param sessionId 会话标识（null 时不排队直接执行）
     * @param work      要串行化的流式操作
     * @return 串行执行后的 Flux
     */
    public Flux<ChatStreamChunk> enqueueStream(String sessionId, Flux<ChatStreamChunk> work) {
        if (sessionId == null) return work;
        return Flux.defer(() -> {
            Sinks.One<Void> myGate = Sinks.one();
            Sinks.One<Void> prevGate = gates.put(sessionId, myGate);
            Flux<ChatStreamChunk> execution = prevGate != null
                    ? prevGate.asMono().thenMany(work)
                    : Flux.defer(() -> work);
            return execution.doFinally(s -> myGate.tryEmitEmpty());
        });
    }
}
