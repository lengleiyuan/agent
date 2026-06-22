package cd.lan1akea.core.event;

import reactor.core.publisher.Flux;

/**
 * 事件订阅器接口。
 *
 * @param <T> 订阅的事件类型
 */
@FunctionalInterface
public interface EventSubscriber<T extends DomainEvent> {

    /**
     * 返回事件流。
     *
     * @return 事件流 Flux
     */
    Flux<T> events();
}
