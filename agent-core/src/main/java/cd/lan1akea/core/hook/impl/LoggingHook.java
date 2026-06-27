package cd.lan1akea.core.hook.impl;

import cd.lan1akea.core.hook.*;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * 日志 Hook。
 *
 * 记录所有 Hook 事件的详细信息，用于调试和监控。
 * 通过 getSubscribedEventTypes() 匹配所有事件类型。
 */
public class LoggingHook implements Hook {

    /**
     * Hook 名称
     */
    private final String name;
    /**
     * 事件计数器
     */
    private int eventCount = 0;

    /**
     * 创建指定名称的日志 Hook。
     */
    public LoggingHook(String name) {
        this.name = name;
    }

    /**
     * 创建默认日志 Hook（名称为 LoggingHook）。
     */
    public LoggingHook() {
        this("LoggingHook");
    }

    /**
     * @return Hook 名称
     */
    @Override
    public String getName() { return name; }

    /**
     * 监听所有主流程事件类型。
     */
    @Override
    public Set<HookEventType> getSubscribedEventTypes() {
        return Set.of(
            HookEventType.PRE_REASONING,
            HookEventType.POST_REASONING,
            HookEventType.PRE_TOOL_CALL,
            HookEventType.POST_TOOL_CALL,
            HookEventType.ON_ERROR
        );
    }

    /**
     * 打印事件日志到标准输出。
     */
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

    /**
     * @return 已处理事件数
     */
    public int getEventCount() { return eventCount; }

    /**
     * 重置计数器
     */
    public void reset() { eventCount = 0; }
}
