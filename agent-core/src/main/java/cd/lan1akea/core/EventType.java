package cd.lan1akea.core;

/**
 * Name EventType.java
 * Author lan1akea
 * Date 2026/06/21
 */
public enum EventType {

    /**
     * Reasoning event - Agent thinking and planning.
     *
     * <p>Characteristics:
     * <ul>
     *   <li>Message role: {@link io.agentscope.core.message.MsgRole#ASSISTANT}</li>
     *   <li>Content: TextBlock, ThinkingBlock, and/or tool call requests</li>
     *   <li>Streaming: Supported (multiple events with same message ID)</li>
     * </ul>
     */
    REASONING,

    /**
     * Tool execution result event.
     *
     * <p>Characteristics:
     * <ul>
     *   <li>Message role: {@link io.agentscope.core.message.MsgRole#TOOL}</li>
     *   <li>Content: ToolResultBlock with execution output</li>
     *   <li>Streaming: Supported for long-running tools</li>
     * </ul>
     */
    TOOL_RESULT,

    /**
     * Hint event - Information from RAG, memory, or planning systems.
     *
     * <p>Characteristics:
     * <ul>
     *   <li>Message role: {@link io.agentscope.core.message.MsgRole#USER} or SYSTEM</li>
     *   <li>Content: TextBlock with retrieved or contextual information</li>
     *   <li>Streaming: Not applicable (complete messages only)</li>
     * </ul>
     */
    HINT,

    /**
     * Final result event - The agent's complete response.
     *
     * <p>This is the message returned by {@link Agent#call(io.agentscope.core.message.Msg)}.
     * By default, this event is NOT included in the stream to avoid duplication since it's the return value.
     *
     * <p>Characteristics:
     * <ul>
     *   <li>Message role: {@link io.agentscope.core.message.MsgRole#ASSISTANT}</li>
     *   <li>Content: Final response text</li>
     *   <li>Streaming: Not applicable</li>
     * </ul>
     */
    AGENT_RESULT,

    /**
     * Summary event - Generated when max iterations reached.
     *
     * <p>Characteristics:
     * <ul>
     *   <li>Message role: {@link io.agentscope.core.message.MsgRole#ASSISTANT}</li>
     *   <li>Content: Summary of what was accomplished</li>
     *   <li>Streaming: May support streaming</li>
     * </ul>
     */
    SUMMARY,

    /**
     * Special value to stream all event types (except {@link #AGENT_RESULT}).
     *
     * <p>Use this in {@link StreamOptions} to receive all events without filtering.
     */
    ALL
}
