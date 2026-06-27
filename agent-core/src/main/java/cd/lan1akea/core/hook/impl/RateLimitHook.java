package cd.lan1akea.core.hook.impl;

import cd.lan1akea.core.hook.*;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 频率限制 Hook。
 *
 * 限制工具调用频率，防止过度调用。
 */
public class RateLimitHook implements Hook {

    /**
     * Hook 名称
     */
    private final String name;
    /**
     * 时间窗口内最大调用次数
     */
    private final int maxCallsPerWindow;
    /**
     * 时间窗口（毫秒）
     */
    private final long windowMs;
    /**
     * 当前窗口调用次数
     */
    private final AtomicInteger callCount = new AtomicInteger(0);
    /**
     * 当前窗口开始时间
     */
    private final AtomicLong windowStart = new AtomicLong(0);

    /**
     * 创建频率限制 Hook。
     */
    public RateLimitHook(int maxCallsPerWindow, long windowMs) {
        this.name = "RateLimitHook";
        this.maxCallsPerWindow = maxCallsPerWindow;
        this.windowMs = windowMs;
    }

    /**
     * 创建默认频率限制 Hook（每分钟最多 10 次）。
     */
    public RateLimitHook() {
        this(10, 60_000);
    }

    /**
     * @return Hook 名称
     */
    @Override
    public String getName() { return name; }

    /**
     * @return PRE_TOOL_CALL 事件类型
     */
    @Override
    public Set<HookEventType> getSubscribedEventTypes() {
        return Set.of(HookEventType.PRE_TOOL_CALL);
    }

    /**
     * 最高优先级（10），在权限校验之前执行。
     */
    @Override
    public int getPriority() { return 10; }

    /**
     * 检查工具调用频率，超限则终止。
     */
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
