package cd.lan1akea.core.state;

import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存状态存储（测试用）。
 */
public class InMemoryStateStore implements StateStore {

    private final Map<String, AgentState> store = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> save(AgentState state) {
        store.put(state.getSessionId(), state);
        return Mono.empty();
    }

    @Override
    public Mono<AgentState> loadLatest(String sessionId) {
        AgentState state = store.get(sessionId);
        return state != null ? Mono.just(state) : Mono.empty();
    }

    @Override
    public Mono<Void> deleteBySession(String sessionId) {
        store.remove(sessionId);
        return Mono.empty();
    }
}
