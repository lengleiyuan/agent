package cd.lan1akea.core.hook.impl;

import cd.lan1akea.core.hook.*;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 频率限制 Hook。
 * <p>
 * 限制工具调用频率，防止过度调用。
 * </p>
 */
public class RateLimitHook implements Hook {

    private final String name;
    private final int maxCallsPerWindow;
    private final long windowMs;
    private final AtomicInteger callCount = new AtomicInteger(0);
    private final AtomicLong windowStart = new AtomicLong(0);

    public RateLimitHook(int maxCallsPerWindow, long windowMs) {
        this.name = "RateLimitHook";
        this.maxCallsPerWindow = maxCallsPerWindow;
        this.windowMs = windowMs;
    }

    public RateLimitHook() {
        this(10, 60_000); // 默认每分钟最多10次工具调用
    }

    @Override
    public String getName() { return name; }

    @Override
    public Set<HookEventType> getSubscribedEventTypes() {
        return Set.of(HookEventType.PRE_TOOL_CALL);
    }

    @Override
    public int getPriority() { return 10; } // 优先级最高，在权限校验之前

    @Override
    public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
        long now = System.currentTimeMillis();

        // 检查时间窗口
        long window = windowStart.get();
        if (window == 0 || now - window > windowMs) {
            // 新窗口
            windowStart.set(now);
            callCount.set(0);
        }

        int count = callCount.incrementAndGet();
        if (count > maxCallsPerWindow) {
            return Mono.just(HookResult.abort(
                "工具调用频率超限: " + count + "/" + maxCallsPerWindow
                + " 每 " + (windowMs / 1000) + " 秒"));
        }

        return Mono.just(HookResult.continue_());
    }
}
