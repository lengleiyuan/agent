package cd.lan1akea.core.hook.impl;

import cd.lan1akea.core.hook.*;
import reactor.core.publisher.Mono;

/**
 * 日志 Hook。
 * <p>
 * 记录所有 Hook 事件的详细信息，用于调试和监控。
 * 订阅所有事件类型（通过动态匹配实现）。
 * </p>
 */
public class LoggingHook implements PreReasoningHook, PostReasoningHook,
                                     PreActingHook, PostActingHook,
                                     PreToolCallHook, PostToolCallHook,
                                     ErrorHook {

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
    public HookEventType getSubscribedEventType() {
        // 由 HookChain 按接口类型匹配，此处返回最常用的类型
        return HookEventType.PRE_REASONING;
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
