package cd.lan1akea.core.hook;

import cd.lan1akea.core.util.IdGenerator;

/**
 * 中断事件。
 * 用于人机协同场景，包含中断原因和恢复所需的上下文。
 */
public class InterruptEvent extends HookEvent {

    /**
     * 创建中断事件。
     */
    public InterruptEvent(String reason, String toolName) {
        super(HookEventType.ON_INTERRUPT);
        setPayload("interruptId", IdGenerator.nextIdStr());
        setPayload("reason", reason);
        setPayload("toolName", toolName);
        setPayload("resolved", false);
    }

    /**
     * @return 中断ID（用于恢复时匹配）
     */
    public String getInterruptId() { return getPayload("interruptId"); }

    /**
     * @return 中断原因
     */
    public String getReason() { return getPayload("reason"); }

    /**
     * @return 触发中断的工具名
     */
    public String getToolName() { return getPayload("toolName"); }

    /**
     * @return 是否已解决
     */
    public boolean isResolved() {
        Boolean resolved = getPayload("resolved");
        return resolved != null && resolved;
    }

    /**
     * 标记为已解决
     */
    public void resolve(Object resolution) {
        setPayload("resolved", true);
        setPayload("resolution", resolution);
    }

    /**
     * @return 人工决策结果
     */
    public Object getResolution() { return getPayload("resolution"); }
}
