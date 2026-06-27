package cd.lan1akea.core.hook;

/**
 * 错误事件。
 * 在 ON_ERROR 阶段携带异常信息。
 */
public class ErrorEvent extends HookEvent {

    /**
     * 创建错误事件。
     */
    public ErrorEvent(Throwable error) {
        super(HookEventType.ON_ERROR);
        setPayload("error", error);
        if (error.getMessage() != null) {
            setPayload("errorMessage", error.getMessage());
        }
        setPayload("errorType", error.getClass().getName());
    }

    /**
     * @return 异常对象
     */
    public Throwable getError() {
        return getPayload("error");
    }

    /**
     * @return 异常消息
     */
    public String getErrorMessage() {
        return getPayload("errorMessage");
    }
}
