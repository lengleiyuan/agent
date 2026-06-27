package cd.lan1akea.core.memory;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 记忆接口。
 * 业务方可根据需要接入 Redis、MySQL、ReMe、Mem0 等后端。
 * 参考 AgentScope ReMe 的双层记忆模型：
 * 短期记忆：会话内消息列表（由 LoopContext.messages 承载）
 * 长期记忆：跨会话持久化（本接口）
 */
public interface Memory {


    /**
     * 存储一条记忆。返回记忆 ID。
     */
    Mono<String> store(MemoryEntry entry);

    /**
     * 批量存储。返回记忆 ID 列表。
     */
    default Flux<String> storeBatch(Flux<MemoryEntry> entries) {
        return entries.flatMap(this::store);
    }


    /**
     * 语义检索（向量/embedding 相似度）。
     */
    Flux<MemoryEntry> retrieve(MemoryRetrievalQuery query);

    /**
     * 全文关键词检索（BM25 / FTS5 等）。
     */
    default Flux<MemoryEntry> search(String keyword, int topK) {
        return retrieve(new MemoryRetrievalQuery(keyword, topK, null, null));
    }

    /**
     * 按 ID 获取单条记忆。
     */
    Mono<MemoryEntry> get(String id);

    /**
     * 获取最近 N 条记忆（时间倒序）。
     */
    Flux<MemoryEntry> recent(int limit);

    /**
     * 获取指定时间范围内的记忆。
     */
    Flux<MemoryEntry> range(long fromEpochMs, long toEpochMs, int limit);


    /**
     * 更新记忆内容（保留原 ID）。
     */
    Mono<Void> update(String id, MemoryEntry entry);

    /**
     * 标记记忆重要性。
     */
    default Mono<Void> markImportance(String id, double importance) {
        return get(id).flatMap(e -> {
            e.setImportance(importance);
            return update(id, e).then();
        });
    }

    /**
     * 按 ID 删除。
     */
    Mono<Void> forget(String id);

    /**
     * 按租户删除。
     */
    Mono<Void> forgetByTenant(String tenantId);

    /**
     * 按用户删除。
     */
    Mono<Void> forgetByUser(String userId);

    /**
     * 按会话删除。
     */
    Mono<Void> forgetBySession(String sessionId);

    /**
     * 清空所有。
     */
    Mono<Void> clear();
}
