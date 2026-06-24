package cd.lan1akea.core.hook.impl;

import cd.lan1akea.core.hook.*;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * 日志 Hook。
 * <p>
 * 记录所有 Hook 事件的详细信息，用于调试和监控。
 * 通过 {@link #getSubscribedEventTypes()} 匹配所有事件类型。
 * </p>
 */
public class LoggingHook implements Hook {

    private final String name;
    private int eventCount = 0;

    public LoggingHook(String name) {
        this.name = name;
    }

    public LoggingHook() {
        this("LoggingHook");
    }

    @Override
    public String getName() { return name; }

    @Override
    public Set<HookEventType> getSubscribedEventTypes() {
        return Set.of(
            HookEventType.PRE_REASONING,
            HookEventType.POST_REASONING,
            HookEventType.PRE_ACTING,
            HookEventType.POST_ACTING,
            HookEventType.PRE_TOOL_CALL,
            HookEventType.POST_TOOL_CALL,
            HookEventType.ON_ERROR,
            HookEventType.ON_STREAM_CHUNK,
            HookEventType.ON_SUMMARY,
            HookEventType.PRE_CALL,
            HookEventType.POST_CALL
        );
    }

    @Override
    public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
        eventCount++;
        String eventType = context != null && event != null
            ? event.getEventType() : "unknown";
        System.out.printf("[%s] #%d | 事件=%s | Agent=%s | 迭代=%d%n",
            name, eventCount, eventType,
            context != null ? context.getAgentName() : "?",
            context != null ? context.getCurrentIteration() : 0);
        return Mono.just(HookResult.continue_());
    }

    /** @return 已处理事件数 */
    public int getEventCount() { return eventCount; }

    /** 重置计数器 */
    public void reset() { eventCount = 0; }
}
