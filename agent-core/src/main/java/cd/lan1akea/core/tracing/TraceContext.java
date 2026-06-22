package cd.lan1akea.core.tracing;

import java.util.Stack;
import java.util.UUID;

/**
 * 追踪上下文。
 * <p>
 * 管理一次请求的追踪链。
 * </p>
 */
public class TraceContext {

    private final String traceId;
    private final Stack<TraceSpan> spans;

    public TraceContext() {
        this.traceId = UUID.randomUUID().toString();
        this.spans = new Stack<>();
    }

    /** 开始新的 Span */
    public TraceSpan startSpan(String operationName) {
        String parentSpanId = spans.isEmpty() ? null : spans.peek().getSpanId();
        TraceSpan span = new TraceSpan(traceId, parentSpanId, operationName);
        spans.push(span);
        return span;
    }

    /** 结束当前 Span */
    public void endSpan() {
        if (!spans.isEmpty()) {
            spans.pop().end();
        }
    }

    public String getTraceId() { return traceId; }
}
