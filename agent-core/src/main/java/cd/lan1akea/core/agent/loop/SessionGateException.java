package cd.lan1akea.core.agent.loop;

/**
 * 会话门控异常。锁获取超时或 Redis 不可达时抛出。
 */
public class SessionGateException extends RuntimeException {

    /**
     * 构造门控异常。
     *
     * @param message 错误描述
     */
    public SessionGateException(String message) {
        super(message);
    }

    /**
     * 构造门控异常（含原因）。
     *
     * @param message 错误描述
     * @param cause   根本原因
     */
    public SessionGateException(String message, Throwable cause) {
        super(message, cause);
    }
}
