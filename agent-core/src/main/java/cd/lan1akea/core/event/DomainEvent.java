package cd.lan1akea.core.event;

import java.util.UUID;

/**
 * 领域事件基类。
 * <p>
 * 所有领域事件（Agent生命周期、工具执行、Hook执行等）均继承此类。
 * 每个事件有唯一ID和发生时间戳。
 * </p>
 */
public abstract class DomainEvent {

    /** 事件唯一ID */
    private final String eventId;

    /** 事件发生时间戳（毫秒） */
    private final long timestamp;

    protected DomainEvent() {
        this.eventId = UUID.randomUUID().toString();
        this.timestamp = System.currentTimeMillis();
    }

    /** @return 事件ID */
    public String getEventId() { return eventId; }

    /** @return 事件时间戳 */
    public long getTimestamp() { return timestamp; }

    /** @return 事件类型标识 */
    public abstract String getEventType();
}
