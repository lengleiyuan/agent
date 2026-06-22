package cd.lan1akea.core.memory;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 长期记忆接口。
 * <p>
 * 支持语义搜索、摘要、模式选择。
 * </p>
 */
public interface LongTermMemory extends Memory {

    /** 摘要模式检索 */
    Flux<MemoryEntry> retrieveSummary(LimitedMemoryRetrievalQuery query);

    /** 按模式检索 */
    Flux<MemoryEntry> retrieve(MemoryRetrievalQuery query, LongTermMemoryMode mode);

    /** 生成摘要 */
    Mono<String> summarize();
}
