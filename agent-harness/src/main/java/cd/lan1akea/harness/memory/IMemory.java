package cd.lan1akea.harness.memory;

import cd.lan1akea.core.memory.Memory;
import cd.lan1akea.core.memory.MemoryEntry;
import cd.lan1akea.core.memory.MemoryRetrievalQuery;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 记忆接口（门面层，可选注入）。
 *
 * 示例：
 *     public class RedisMemory implements IMemory {
 *         public Mono<String> store(MemoryEntry e) { ... }
 *         public Flux<MemoryEntry> retrieve(MemoryRetrievalQuery q) { ... }
 *     }
 *
 *     HarnessAgent.builder()
 *         .memory(new RedisMemory())
 *         .build();
 */
public interface IMemory extends Memory {

    /**
     * 存储记忆条目。
     */
    @Override Mono<String> store(MemoryEntry entry);
    /**
     * 根据查询条件检索记忆。
     */
    @Override Flux<MemoryEntry> retrieve(MemoryRetrievalQuery query);
    /**
     * 根据 ID 获取单条记忆。
     */
    @Override Mono<MemoryEntry> get(String id);
    /**
     * 删除指定 ID 的记忆。
     */
    @Override Mono<Void> forget(String id);
    /**
     * 清空所有记忆。
     */
    @Override Mono<Void> clear();
}
