package cd.lan1akea.core.agent;

import cd.lan1akea.core.event.DomainEvent;
import reactor.core.publisher.Flux;

/**
 * 可观测 Agent 接口。
 * <p>
 * 提供订阅 Agent 内部生命周期事件的能力。
 * </p>
 */
public interface ObservableAgent extends Agent {

    /**
     * 订阅 Agent 事件流。
     *
     * @return Flux&lt;DomainEvent&gt; 事件流
     */
    Flux<DomainEvent> events();

    /**
     * 订阅指定类型的事件。
     *
     * @param eventType 事件类型
     * @return Flux&lt;DomainEvent&gt; 事件流
     */
    Flux<DomainEvent> events(String eventType);
}
