package cd.lan1akea.bootstrap.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * API 层 per-tenant 频率限制过滤器。
 * 基于简化令牌桶：每租户每个窗口最多 N 次请求。
 * 超限返回 429 Too Many Requests。
 */
@Component
@Order(-100)
public class ApiRateLimitFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiRateLimitFilter.class);

    /**
     * 默认每分钟最大请求数
     */
    private static final int DEFAULT_MAX_REQUESTS = 60;
    /**
     * 时间窗口（毫秒）
     */
    private static final long WINDOW_MS = 60_000;

    private record Bucket(AtomicLong windowStart, AtomicInteger count) {}

    private final ConcurrentHashMap<String, Bucket> tenantBuckets = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String tenantId = exchange.getRequest().getHeaders().getFirst("X-Tenant-Id");
        if (tenantId == null || tenantId.isBlank()) {
            return chain.filter(exchange);
        }

        Bucket bucket = tenantBuckets.computeIfAbsent(tenantId,
            k -> new Bucket(new AtomicLong(0), new AtomicInteger(0)));

        long now = System.currentTimeMillis();
        long window = bucket.windowStart.get();
        if (now - window > WINDOW_MS) {
            bucket.windowStart.set(now);
            bucket.count.set(0);
        }

        int count = bucket.count.incrementAndGet();
        if (count > DEFAULT_MAX_REQUESTS) {
            log.warn("Tenant rate limit exceeded: tenant={}, count={}", tenantId, count);
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            exchange.getResponse().getHeaders().set("Retry-After", "60");
            return exchange.getResponse().setComplete();
        }

        return chain.filter(exchange);
    }
}
