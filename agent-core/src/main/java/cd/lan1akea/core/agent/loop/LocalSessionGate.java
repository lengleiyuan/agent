package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.model.ChatStreamChunk;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地内存实现。基于 ConcurrentHashMap.put（原子替换）+ Sinks.One（异步信号）。
 * 单实例部署使用。
 */
public class LocalSessionGate implements SessionGate {

    private final ConcurrentHashMap<String, Sinks.One<Void>> gates = new ConcurrentHashMap<>();

    private GateHandle acquire(String sessionId) {
        Sinks.One<Void> mine = Sinks.one();
        Sinks.One<Void> prev = gates.put(sessionId, mine);
        return new GateHandle(mine, prev);
    }

    @Override
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

    @Override
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
