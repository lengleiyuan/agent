package cd.lan1akea.core.metrics;

/**
 * Agent 指标收集 SPI。
 * 实现类可接入 Micrometer / Prometheus / OpenTelemetry。
 * 默认 {@link #NOOP} 为无操作实现，不依赖任何外部库。
 *
 * <p>注入方式：</p>
 * <pre>{@code
 * agent.setMetrics(myMetrics);
 * // 或通过 HarnessAgent Builder
 * HarnessAgent.builder()...metrics(myMetrics)...
 * }</pre>
 */
public interface AgentMetrics {

    /** LLM 调用完成时记录。 */
    void recordLlmCall(String model, String provider, long latencyMs,
                       int promptTokens, int completionTokens, boolean success, String errorType);

    /** 工具调用完成时记录。 */
    void recordToolCall(String toolName, String riskLevel, long latencyMs,
                        boolean success, boolean approved, String errorType);

    /** 一次 ReAct 迭代完成时记录。 */
    void recordIteration(String agentName, String sessionId, int iteration,
                         int toolCallsThisIteration);

    /** 审批事件记录。 */
    void recordApproval(String toolName, String sessionId, String decision);

    /** Token 消耗记录。 */
    void recordTokenUsage(String agentName, String sessionId, String model,
                          int promptTokens, int completionTokens);

    /** 无操作默认实例。 */
    AgentMetrics NOOP = new AgentMetrics() {
        @Override public void recordLlmCall(String m, String p, long l, int pt, int ct, boolean s, String e) {}
        @Override public void recordToolCall(String t, String r, long l, boolean s, boolean a, String e) {}
        @Override public void recordIteration(String a, String s, int i, int tc) {}
        @Override public void recordApproval(String t, String s, String d) {}
        @Override public void recordTokenUsage(String a, String s, String m, int p, int c) {}
    };
}
