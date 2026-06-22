package cd.lan1akea.core.agent;

import cd.lan1akea.core.event.DomainEvent;

/**
 * Agent 生命周期事件。
 */
public class AgentEvent extends DomainEvent {

    private final AgentEventType agentEventType;
    private final String agentName;

    public AgentEvent(AgentEventType agentEventType, String agentName) {
        this.agentEventType = agentEventType;
        this.agentName = agentName;
    }

    /** @return Agent 事件类型 */
    public AgentEventType getAgentEventType() { return agentEventType; }

    /** @return Agent 名称 */
    public String getAgentName() { return agentName; }

    @Override
    public String getEventType() {
        return "agent:" + agentEventType.name().toLowerCase();
    }
}
