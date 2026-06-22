package cd.lan1akea.core.hook;

import cd.lan1akea.core.event.DomainEvent;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Hook 事件。
 * <p>
 * 携带 Hook 执行上下文中的所有数据，Hook 实现可通过 getPayload() / setPayload() 读写数据。
 * </p>
 */
public class HookEvent extends DomainEvent {

    /** Hook 事件类型 */
    private final HookEventType eventType;

    /** 事件载荷（可变，Hook 可修改） */
    private final Map<String, Object> payload;

    public HookEvent(HookEventType eventType) {
        this(eventType, new HashMap<>());
    }

    public HookEvent(HookEventType eventType, Map<String, Object> payload) {
        this.eventType = eventType;
        this.payload = new HashMap<>(payload != null ? payload : new HashMap<>());
    }

    /**
     * 获取载荷值。
     */
    @SuppressWarnings("unchecked")
    public <T> T getPayload(String key) {
        return (T) payload.get(key);
    }

    /**
     * 设置载荷值。
     */
    public void setPayload(String key, Object value) {
        payload.put(key, value);
    }

    /**
     * 获取只读载荷。
     */
    public Map<String, Object> getPayload() {
        return Collections.unmodifiableMap(payload);
    }

    /** @return Hook 事件类型 */
    public HookEventType getHookEventType() { return eventType; }

    @Override
    public String getEventType() {
        return "hook:" + eventType.name().toLowerCase();
    }
}
