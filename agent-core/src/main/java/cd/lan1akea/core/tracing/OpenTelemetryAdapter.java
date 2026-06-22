package cd.lan1akea.core.tracing;

/**
 * OpenTelemetry 适配器（预留）。
 * <p>
 * 将框架追踪信息导出到 OpenTelemetry 兼容的后端。
 * </p>
 */
public class OpenTelemetryAdapter {

    private boolean enabled = false;

    /**
     * 导出 Span。
     */
    public void export(TraceSpan span) {
        if (!enabled) return;
        // TODO: OTel SDK 接入后实现
        // 1. 转换为 OTel Span
        // 2. 通过 OTel Exporter 导出
    }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isEnabled() { return enabled; }
}
