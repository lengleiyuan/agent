package cd.lan1akea.core.memory;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 记忆存储接口。
 */
public interface MemoryStore {

    /** 保存记忆 */
    Mono<Void> save(MemoryEntry entry);

    /** 按租户检索 */
    Flux<MemoryEntry> findByTenant(long tenantId, String query, int limit);

    /** 按用户检索 */
    Flux<MemoryEntry> findByUser(long tenantId, long userId, String query, int limit);

    /** 删除单条记忆 */
    Mono<Void> deleteById(String id);

    /** 清空租户记忆 */
    Mono<Void> deleteByTenant(long tenantId);
}
