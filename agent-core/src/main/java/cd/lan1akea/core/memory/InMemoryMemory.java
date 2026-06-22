package cd.lan1akea.core.memory;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存记忆实现（实现 Memory 接口）。
 */
public class InMemoryMemory implements Memory {

    private final Map<String, MemoryEntry> store = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> store(MemoryEntry entry) {
        store.put(entry.getId(), entry);
        return Mono.empty();
    }

    @Override
    public Flux<MemoryEntry> retrieve(MemoryRetrievalQuery query) {
        return Flux.fromStream(store.values().stream()
            .filter(e -> query.getTenantId() == null
                || e.getTenantId() == query.getTenantId())
            .filter(e -> query.getUserId() == null
                || (e.getUserId() != null && e.getUserId().equals(query.getUserId())))
            .filter(e -> query.getQuery() == null
                || e.getContent().contains(query.getQuery()))
            .limit(query.getMaxResults()));
    }

    @Override
    public Mono<Void> forget(String id) {
        store.remove(id);
        return Mono.empty();
    }

    @Override
    public Mono<Void> clear() {
        store.clear();
        return Mono.empty();
    }
}
