package cd.lan1akea.core.agent;

/**
 * 流式 Agent 能力标记接口。
 */
public interface StreamableAgent extends Agent {
    /**
     * 是否支持流式输出。
     */
    default boolean supportsStreaming() {
        return true;
    }
}
