package cd.lan1akea.core.agent;

import cd.lan1akea.core.event.DomainEvent;
import cd.lan1akea.core.message.Msg;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 可观测 Agent 接口。
 * <p>
 * 提供观察消息和订阅 Agent 内部生命周期事件的能力。
 * </p>
 */
public interface ObservableAgent extends Agent {

    /**
     * 被动观察消息，不产生回复。
     * <p>
     * 用于多 Agent 场景：A 观察 B 的输出但不插话。
     * 仅触发 Hook 链和事件，不返回 ChatResponse。
     * </p>
     */
    Mono<Void> observe(Msg message);

    /** 订阅 Agent 所有事件流 */
    Flux<DomainEvent> events();

    /** 订阅指定类型的事件 */
    Flux<DomainEvent> events(String eventType);
}
