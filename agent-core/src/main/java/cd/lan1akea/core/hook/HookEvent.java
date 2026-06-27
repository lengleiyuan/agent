package cd.lan1akea.core.hook;


import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Hook 事件。
 * 携带 Hook 执行上下文中的所有数据，Hook 实现可通过 getPayload / setPayload 读写数据。
 */
public class HookEvent {

    /**
     * Hook 事件类型
     */
    private final HookEventType eventType;

    /**
     * 事件载荷（可变，Hook 可修改）
     */
    private final Map<String, Object> payload;

    /**
     * 创建指定类型的 Hook 事件，带空载荷。
     */
    public HookEvent(HookEventType eventType) {
        this(eventType, new HashMap<>());
    }

    /**
     * 创建指定类型和载荷的 Hook 事件。
     */
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

    /**
     * @return Hook 事件类型
     */
    public HookEventType getHookEventType() { return eventType; }

    /**
     * @return 事件类型标识字符串
     */
    public String getEventType() {
        return eventType != null ? "hook:" + eventType.name().toLowerCase() : "hook:aroundCall";
    }
}
