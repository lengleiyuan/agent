package cd.lan1akea.core.event;

import reactor.core.publisher.Mono;

/**
 * 事件发布器接口。
 */
public interface EventPublisher {

    /**
     * 发布领域事件。
     *
     * @param event 领域事件
     * @return Mono&lt;Void&gt; 发布完成信号
     */
    Mono<Void> publish(DomainEvent event);
}
