package cd.lan1akea.core.agent;

import cd.lan1akea.core.event.EventBus;
import cd.lan1akea.core.event.EventPublisher;
import reactor.core.publisher.Mono;

/**
 * Agent 事件源。
 * <p>
 * 负责发射 Agent 生命周期事件到事件总线。
 * </p>
 */
public class AgentEventSource implements EventPublisher {

    private final EventBus eventBus;

    public AgentEventSource(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    /**
     * 发射 Agent 事件。
     */
    public Mono<Void> emit(AgentEventType type, String agentName) {
        return publish(new AgentEvent(type, agentName));
    }

    @Override
    public Mono<Void> publish(cd.lan1akea.core.event.DomainEvent event) {
        return eventBus.publish(event);
    }
}
