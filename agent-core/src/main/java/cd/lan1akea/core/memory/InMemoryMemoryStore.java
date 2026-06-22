package cd.lan1akea.core.memory;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存记忆存储（测试用）。
 */
public class InMemoryMemoryStore implements MemoryStore {

    private final Map<String, MemoryEntry> store = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> save(MemoryEntry entry) {
        store.put(entry.getId(), entry);
        return Mono.empty();
    }

    @Override
    public Flux<MemoryEntry> findByTenant(long tenantId, String query, int limit) {
        return Flux.fromStream(store.values().stream()
            .filter(e -> e.getTenantId() == tenantId)
            .filter(e -> query == null || e.getContent().contains(query))
            .limit(limit));
    }

    @Override
    public Flux<MemoryEntry> findByUser(long tenantId, long userId, String query, int limit) {
        return Flux.fromStream(store.values().stream()
            .filter(e -> e.getTenantId() == tenantId
                && e.getUserId() != null && e.getUserId() == userId)
            .filter(e -> query == null || e.getContent().contains(query))
            .limit(limit));
    }

    @Override
    public Mono<Void> deleteById(String id) {
        store.remove(id);
        return Mono.empty();
    }

    @Override
    public Mono<Void> deleteByTenant(long tenantId) {
        store.entrySet().removeIf(e -> e.getValue().getTenantId() == tenantId);
        return Mono.empty();
    }
}
