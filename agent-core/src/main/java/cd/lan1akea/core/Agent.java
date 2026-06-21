package cd.lan1akea.core;

import cd.lan1akea.core.message.Message;

/**
 * Name Agent.java
 * Author lan1akea
 * Date 2026/06/21
 */
public interface Agent {

    /**
     * Get the unique identifier for this agent.
     *
     * @return Agent ID
     */
    String getAgentId();

    /**
     * Get the name of this agent.
     *
     * @return Agent name
     */
    String getName();

    /**
     * Get the description of this agent.
     *
     * @return Agent description
     */
    default String getDescription() {
        return "Agent(" + getAgentId() + ") " + getName();
    }

    /**
     * Interrupt the current agent execution.
     * This method sets an interrupt flag that will be checked by the agent at appropriate
     * checkpoints during execution. The interruption is cooperative and may not take effect
     * immediately.
     */
    void interrupt();

    /**
     * Interrupt the current agent execution with a user message.
     * This method sets an interrupt flag and associates a user message with the interruption.
     * The interruption is cooperative and may not take effect immediately.
     *
     * @param msg User message associated with the interruption
     */
    void interrupt(Message msg);
}
