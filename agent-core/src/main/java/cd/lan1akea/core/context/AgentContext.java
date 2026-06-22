package cd.lan1akea.core.context;

/**
 * Agent 执行上下文。
 */
public class AgentContext extends RuntimeContext {

    private final String modelProvider;
    private final String modelName;
    private final int maxIterations;
    private final int currentIteration;

    public AgentContext(String tenantId, String userId, String sessionId,
                         String agentName, String modelProvider, String modelName,
                         int maxIterations, int currentIteration) {
        super(tenantId, userId, sessionId, agentName, null);
        this.modelProvider = modelProvider;
        this.modelName = modelName;
        this.maxIterations = maxIterations;
        this.currentIteration = currentIteration;
    }

    public String getModelProvider() { return modelProvider; }
    public String getModelName() { return modelName; }
    public int getMaxIterations() { return maxIterations; }
    public int getCurrentIteration() { return currentIteration; }
}
