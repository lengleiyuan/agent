package cd.lan1akea.core.tracing;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 追踪 Span。
 * <p>
 * 记录一次操作的追踪信息。
 * </p>
 */
public class TraceSpan {

    private final String spanId;
    private final String traceId;
    private final String parentSpanId;
    private final String operationName;
    private final long startTimeMs;
    private long endTimeMs;
    private final Map<String, String> attributes;

    public TraceSpan(String traceId, String parentSpanId, String operationName) {
        this.spanId = UUID.randomUUID().toString();
        this.traceId = traceId;
        this.parentSpanId = parentSpanId;
        this.operationName = operationName;
        this.startTimeMs = Instant.now().toEpochMilli();
        this.attributes = new HashMap<>();
    }

    public void end() {
        this.endTimeMs = Instant.now().toEpochMilli();
    }

    public void setAttribute(String key, String value) {
        attributes.put(key, value);
    }

    public long getDurationMs() { return endTimeMs - startTimeMs; }
    public String getSpanId() { return spanId; }
    public String getTraceId() { return traceId; }
    public String getOperationName() { return operationName; }
    public Map<String, String> getAttributes() { return attributes; }
}
