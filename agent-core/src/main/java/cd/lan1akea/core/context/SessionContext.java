package cd.lan1akea.core.context;

/**
 * 会话上下文。
 */
public class SessionContext extends RuntimeContext {

    private final String sessionId;
    private final int turnCount;
    private final long totalTokens;
    private final java.time.Duration elapsed;

    public SessionContext(String tenantId, String userId, String sessionId,
                           String agentName, int turnCount, long totalTokens,
                           java.time.Duration elapsed) {
        super(tenantId, userId, sessionId, agentName, null);
        this.sessionId = sessionId;
        this.turnCount = turnCount;
        this.totalTokens = totalTokens;
        this.elapsed = elapsed;
    }

    @Override
    public String getSessionId() { return sessionId; }
    public int getTurnCount() { return turnCount; }
    public long getTotalTokens() { return totalTokens; }
    public java.time.Duration getElapsed() { return elapsed; }
}
