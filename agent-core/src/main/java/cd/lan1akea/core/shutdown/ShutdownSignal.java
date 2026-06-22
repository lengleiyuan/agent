package cd.lan1akea.core.shutdown;

/**
 * 停机信号。
 */
public class ShutdownSignal {

    private final String reason;
    private final long timestamp;

    public ShutdownSignal(String reason) {
        this.reason = reason;
        this.timestamp = System.currentTimeMillis();
    }

    public String getReason() { return reason; }
    public long getTimestamp() { return timestamp; }
}
