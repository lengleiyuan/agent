package cd.lan1akea.core.exception;

/**
 * 框架基础异常，所有 Agent 异常的父类。
 */
public class AgentException extends RuntimeException {

    /**
     * 错误码
     */
    private final String errorCode;

    /**
     * @param errorCode 错误码
     * @param message   错误描述
     */
    public AgentException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * @param errorCode 错误码
     * @param message   错误描述
     * @param cause     原始异常
     */
    public AgentException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * @return 错误码
     */
    public String getErrorCode() {
        return errorCode;
    }
}
