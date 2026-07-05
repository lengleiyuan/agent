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
     * 获取会话门控，返回当前和上一个门的句柄。
     */
    private GateHandle acquire(String sessionId) {
        Sinks.One<Void> mine = Sinks.one();
        Sinks.One<Void> prev = gates.put(sessionId, mine);
        return new GateHandle(mine, prev);
    }

    /**
     * 串行化 Mono 执行。sessionId 为 null 时不排队。
     *
     * @param sessionId 会话标识（null 时不排队直接执行）
     * @param work      要串行化的操作
     * @param <T>       结果类型
     * @return 串行执行后的 Mono
     */
    public <T> Mono<T> enqueue(String sessionId, Mono<T> work) {
        if (sessionId == null) return work;
        return Mono.defer(() -> {
            GateHandle gate = acquire(sessionId);
            Mono<T> execution = gate.previous != null
                    ? gate.previous.asMono().then(work)
                    : Mono.defer(() -> work);
            return execution.doFinally(s -> gate.mine.tryEmitEmpty());
        });
    }

    /**
     * 串行化 Flux 执行。sessionId 为 null 时不排队。
     *
     * @param sessionId 会话标识（null 时不排队直接执行）
     * @param work      要串行化的流式操作
     * @return 串行执行后的 Flux
     */
    public Flux<ChatStreamChunk> enqueueStream(String sessionId, Flux<ChatStreamChunk> work) {
        if (sessionId == null) return work;
        return Flux.defer(() -> {
            GateHandle gate = acquire(sessionId);
            Flux<ChatStreamChunk> execution = gate.previous != null
                    ? gate.previous.asMono().thenMany(work)
                    : Flux.defer(() -> work);
            return execution.doFinally(s -> gate.mine.tryEmitEmpty());
        });
    }

    private static class GateHandle {
        final Sinks.One<Void> mine;
        final Sinks.One<Void> previous;
        GateHandle(Sinks.One<Void> mine, Sinks.One<Void> previous) {
            this.mine = mine; this.previous = previous;
        }
    }
}
