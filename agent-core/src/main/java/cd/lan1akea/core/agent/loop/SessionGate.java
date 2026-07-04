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
 */
public class SessionGate {

    private final ConcurrentHashMap<String, Sinks.One<Void>> gates = new ConcurrentHashMap<>();

    /**
     * 串行化 Mono 执行。sessionId 为 null 时不排队。
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
