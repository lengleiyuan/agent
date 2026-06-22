package cd.lan1akea.core.exception;

/**
 * 会话不存在异常。
 */
public class SessionNotFoundException extends AgentException {

    private final String sessionId;

    public SessionNotFoundException(String sessionId) {
        super("SES_001", "会话不存在: " + sessionId);
        this.sessionId = sessionId;
    }

    /** @return 会话ID */
    public String getSessionId() { return sessionId; }
}
