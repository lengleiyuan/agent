package cd.lan1akea.harness;

/**
 * SDK 异常。
 */
public class HarnessException extends RuntimeException {

    public HarnessException(String message) {
        super(message);
    }

    public HarnessException(String message, Throwable cause) {
        super(message, cause);
    }
}
