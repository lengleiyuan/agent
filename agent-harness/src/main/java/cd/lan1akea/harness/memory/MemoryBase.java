package cd.lan1akea.harness.memory;

import cd.lan1akea.core.memory.MemoryEntry;
import cd.lan1akea.core.memory.MemoryRetrievalQuery;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;

/**
 * 记忆抽象基类——同步实现，框架自动包装为 Mono/Flux。
 * 示例：
 *     public class RedisMemory extends MemoryBase {
 *         protected String doStore(MemoryEntry e) { return redis.set(e.getId(), e); }
 *         protected List<MemoryEntry> doRetrieve(MemoryRetrievalQuery q) { return redis.search(q); }
 *         protected MemoryEntry doGet(String id) { return redis.get(id); }
 *         protected void doForget(String id) { redis.del(id); }
 *         protected void doClear() { redis.flush(); }
 *     }
 */
public abstract class MemoryBase implements cd.lan1akea.core.memory.Memory {

    // ========================================================================
    // 业务实现同步方法
    // ========================================================================

    /**
     * 同步存储记忆条目，返回条目标识。
     */
    protected abstract String doStore(MemoryEntry entry);
    /**
     * 同步检索记忆条目。
     */
    protected abstract List<MemoryEntry> doRetrieve(MemoryRetrievalQuery query);
    /**
     * 同步删除指定 ID 的记忆。
     */
    protected abstract void doForget(String id);
    /**
     * 同步清空所有记忆。
     */
    protected abstract void doClear();

    /**
     * 同步获取单条记忆，默认返回 null。
     */
    protected MemoryEntry doGet(String id) { return null; }
    /**
     * 同步更新记忆条目，默认无操作。
     */
    protected void doUpdate(String id, MemoryEntry entry) {}
    /**
     * 同步获取最近记忆，默认委托给 doRetrieve。
     */
    protected List<MemoryEntry> doRecent(int limit) { return doRetrieve(new MemoryRetrievalQuery()); }
    /**
     * 同步获取范围内记忆，默认返回空列表。
     */
    protected List<MemoryEntry> doRange(long from, long to, int limit) { return Collections.emptyList(); }
    /**
     * 同步按租户删除记忆，默认无操作。
     */
    protected void doForgetByTenant(String tenantId) {}
    /**
     * 同步按用户删除记忆，默认无操作。
     */
    protected void doForgetByUser(String userId) {}
    /**
     * 同步按会话删除记忆，默认无操作。
     */
    protected void doForgetBySession(String sessionId) {}

    // ========================================================================
    // 框架自动包装为 Mono/Flux
    // ========================================================================

    /**
     * 存储记忆条目，委托给 doStore。
     */
    @Override
    public Mono<String> store(MemoryEntry e) {
        return Mono.fromCallable(() -> doStore(e));
    }

    /**
     * 检索记忆条目，委托给 doRetrieve。
     */
    @Override
    public Flux<MemoryEntry> retrieve(MemoryRetrievalQuery q) {
        return Mono.fromCallable(() -> doRetrieve(q)).flatMapMany(Flux::fromIterable);
    }

    /**
     * 获取单条记忆，委托给 doGet。
     */
    @Override
    public Mono<MemoryEntry> get(String id) {
        return Mono.fromCallable(() -> doGet(id));
    }

    /**
     * 更新记忆条目，委托给 doUpdate。
     */
    @Override
    public Mono<Void> update(String id, MemoryEntry e) {
        return Mono.fromRunnable(() -> doUpdate(id, e));
    }

    /**
     * 获取最近记忆，委托给 doRecent。
     */
    @Override
    public Flux<MemoryEntry> recent(int limit) {
        return Mono.fromCallable(() -> doRecent(limit)).flatMapMany(Flux::fromIterable);
    }

    /**
     * 获取范围内的记忆，委托给 doRange。
     */
    @Override
    public Flux<MemoryEntry> range(long from, long to, int limit) {
        return Mono.fromCallable(() -> doRange(from, to, limit)).flatMapMany(Flux::fromIterable);
    }

    /**
     * 删除指定 ID 的记忆，委托给 doForget。
     */
    @Override
    public Mono<Void> forget(String id) {
        return Mono.fromRunnable(() -> doForget(id));
    }

    /**
     * 按租户删除记忆，委托给 doForgetByTenant。
     */
    @Override
    public Mono<Void> forgetByTenant(String t) {
        return Mono.fromRunnable(() -> doForgetByTenant(t));
    }

    /**
     * 按用户删除记忆，委托给 doForgetByUser。
     */
    @Override
    public Mono<Void> forgetByUser(String u) {
        return Mono.fromRunnable(() -> doForgetByUser(u));
    }

    /**
     * 按会话删除记忆，委托给 doForgetBySession。
     */
    @Override
    public Mono<Void> forgetBySession(String s) {
        return Mono.fromRunnable(() -> doForgetBySession(s));
    }

    /**
     * 清空所有记忆，委托给 doClear。
     */
    @Override
    public Mono<Void> clear() {
        return Mono.fromRunnable(this::doClear);
    }
}
