package cd.lan1akea.core.memory;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 记忆接口（短期/工作记忆）。
 */
public interface Memory {

    /** 存储记忆 */
    Mono<Void> store(MemoryEntry entry);

    /** 检索记忆 */
    Flux<MemoryEntry> retrieve(MemoryRetrievalQuery query);

    /** 遗忘记忆 */
    Mono<Void> forget(String id);

    /** 清空所有记忆 */
    Mono<Void> clear();
}
