package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.model.ChatStreamChunk;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Redis 分布式锁实现。
 * 基于 SET NX PX + 看门狗续期，支持多实例部署时同一 session 串行化。
 *
 * <p>锁获取不设内部超时——持续重试直到拿到锁。
 * 超时控制由调用方通过外层 timeout 操作符统一负责（如 execConfig.totalTimeoutMs）。
 */
public class RedisSessionGate implements SessionGate {

    private static final String LOCK_KEY_PREFIX = "agent:session:gate:";
    private static final long DEFAULT_LOCK_TTL_MS = 30_000;
    private static final long DEFAULT_RETRY_INTERVAL_MS = 100;

    private final RedisClient redisClient;
    private final String agentName;
    private final long lockTtlMs;
    private final long retryIntervalMs;
    private final ScheduledExecutorService watchdog;

    /**
     * 维护每个锁的续期任务，key 为 lockKey，value 为 ScheduledFuture。
     */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> renewals = new ConcurrentHashMap<>();

    public RedisSessionGate(RedisClient redisClient, String agentName) {
        this(redisClient, agentName, DEFAULT_LOCK_TTL_MS, DEFAULT_RETRY_INTERVAL_MS);
    }

    public RedisSessionGate(RedisClient redisClient, String agentName,
                             long lockTtlMs, long retryIntervalMs) {
        this.redisClient = redisClient;
        this.agentName = agentName;
        this.lockTtlMs = lockTtlMs;
        this.retryIntervalMs = retryIntervalMs;
        this.watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "redis-session-gate-watchdog");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public <T> Mono<T> enqueue(String sessionId, Mono<T> work) {
        if (sessionId == null) return work;
        String lockKey = buildLockKey(sessionId);
        String lockValue = UUID.randomUUID().toString();
        return acquireLock(lockKey, lockValue)
                .then(Mono.defer(() -> {
                    ScheduledFuture<?> renewal = startRenewal(lockKey, lockValue);
                    return work.doFinally(s -> {
                        cancelRenewal(lockKey, renewal);
                        releaseLock(lockKey, lockValue);
                    });
                }));
    }

    @Override
    public Flux<ChatStreamChunk> enqueueStream(String sessionId, Flux<ChatStreamChunk> work) {
        if (sessionId == null) return work;
        String lockKey = buildLockKey(sessionId);
        String lockValue = UUID.randomUUID().toString();
        return acquireLock(lockKey, lockValue)
                .thenMany(Flux.defer(() -> {
                    ScheduledFuture<?> renewal = startRenewal(lockKey, lockValue);
                    return work.doFinally(s -> {
                        cancelRenewal(lockKey, renewal);
                        releaseLock(lockKey, lockValue);
                    });
                }));
    }

    /**
     * 关闭看门狗线程池。
     */
    public void shutdown() {
        watchdog.shutdownNow();
    }

    private String buildLockKey(String sessionId) {
        return LOCK_KEY_PREFIX + agentName + ":" + sessionId;
    }

    /**
     * 循环尝试 SET NX PX 获取锁，不设内部超时。
     *
     * <p>持续重试直到拿到锁或该订阅被外部取消（由外层 timeout 触发）。
     * Redis 不可达时映射为 SessionGateException。
     */
    private Mono<Void> acquireLock(String lockKey, String lockValue) {
        return Mono.defer(() ->
                redisClient.set(lockKey, lockValue, "NX", "PX", lockTtlMs)
                        .flatMap(result -> {
                            if ("OK".equals(result)) {
                                return Mono.empty();
                            }
                            return Mono.delay(Duration.ofMillis(retryIntervalMs))
                                    .then(Mono.defer(() -> acquireLock(lockKey, lockValue)));
                        })
        ).onErrorMap(e -> new SessionGateException("Lock acquire error: " + lockKey, e));
    }

    /**
     * 启动看门狗续期。
     */
    private ScheduledFuture<?> startRenewal(String lockKey, String lockValue) {
        long intervalMs = lockTtlMs / 3;
        ScheduledFuture<?> future = watchdog.scheduleAtFixedRate(
                () -> redisClient.pexpire(lockKey, lockTtlMs, lockValue),
                intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        renewals.put(lockKey, future);
        return future;
    }

    /**
     * 取消续期任务。
     */
    private void cancelRenewal(String lockKey, ScheduledFuture<?> future) {
        renewals.remove(lockKey);
        if (future != null) {
            future.cancel(false);
        }
    }

    /**
     * 释放锁：DEL lockKey。
     */
    private void releaseLock(String lockKey, String lockValue) {
        redisClient.del(lockKey, lockValue);
    }

    // ============================================================
    // Redis 客户端抽象（内部接口，调用方提供实现）
    // ============================================================

    /**
     * Redis 客户端抽象。调用方实现此接口注入 RedisSessionGate。
     * 仅暴露本实现需要的操作：SET NX PX / PEXPIRE / DEL。
     */
    public interface RedisClient {

        /**
         * SET key value NX PX ttlMs。成功返回 "OK"，key 已存在返回 null。
         */
        Mono<String> set(String key, String value, String nx, String px, long ttlMs);

        /**
         * 续期：PEXPIRE key ttlMs。
         */
        void pexpire(String key, long ttlMs, String expectedValue);

        /**
         * 释放锁：DEL key。
         */
        void del(String key, String expectedValue);
    }
}
