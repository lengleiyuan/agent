package cd.lan1akea.core.middleware;

import cd.lan1akea.core.agent.loop.LoopContext;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 限流中间件。
 * <p>
 * 基于令牌桶算法的简单限流实现。
 * </p>
 */
public class RateLimitingMiddleware implements Middleware {

    private final int maxRequestsPerSecond;
    private final AtomicLong lastRequestTime = new AtomicLong(0);
    private final long minIntervalNanos;

    public RateLimitingMiddleware(int maxRequestsPerSecond) {
        this.maxRequestsPerSecond = maxRequestsPerSecond;
        this.minIntervalNanos = Duration.ofSeconds(1).toNanos() / maxRequestsPerSecond;
    }

    @Override
    public Mono<LoopContext> before(LoopContext ctx) {
        long now = System.nanoTime();
        long last = lastRequestTime.get();
        long elapsed = now - last;

        if (elapsed < minIntervalNanos) {
            long waitNanos = minIntervalNanos - elapsed;
            return Mono.delay(Duration.ofNanos(waitNanos))
                .then(Mono.fromRunnable(() -> lastRequestTime.set(System.nanoTime())))
                .thenReturn(ctx);
        }

        lastRequestTime.set(now);
        return Mono.just(ctx);
    }
}
