package cd.lan1akea.core.event;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 事件总线。
 * <p>
 * 基于 Reactor Sinks.Many 实现的事件发布/订阅机制。
 * 每种事件类型可以有多个订阅者，事件按顺序广播。
 * </p>
 */
public class EventBus {

    // 按事件类型存储 Sink
    private final Map<String, Sinks.Many<DomainEvent>> sinks = new ConcurrentHashMap<>();

    /**
     * 发布事件。
     *
     * @param event 领域事件
     * @return Mono&lt;Void&gt; 发布完成后信号
     */
    public Mono<Void> publish(DomainEvent event) {
        Sinks.Many<DomainEvent> sink = sinks.get(event.getEventType());
        if (sink == null) {
            return Mono.empty();
        }
        return Mono.fromRunnable(() -> {
            Sinks.EmitResult result = sink.tryEmitNext(event);
            if (result.isFailure()) {
                // 重试一次
                sink.emitNext(event, Sinks.EmitFailureHandler.FAIL_FAST);
            }
        });
    }

    /**
     * 订阅指定事件类型。
     *
     * @param eventType 事件类型
     * @return Flux&lt;DomainEvent&gt; 事件流
     */
    public Flux<DomainEvent> subscribe(String eventType) {
        Sinks.Many<DomainEvent> sink = sinks.computeIfAbsent(eventType,
            k -> Sinks.many().multicast().onBackpressureBuffer());
        return sink.asFlux();
    }

    /**
     * 注销事件类型的所有订阅者。
     *
     * @param eventType 事件类型
     */
    public void unsubscribe(String eventType) {
        Sinks.Many<DomainEvent> sink = sinks.remove(eventType);
        if (sink != null) {
            sink.tryEmitComplete();
        }
    }

    /**
     * 获取已注册的事件类型数。
     *
     * @return 事件类型数
     */
    public int getEventTypeCount() {
        return sinks.size();
    }
}
