package cd.lan1akea.core.hook;


import cd.lan1akea.core.CoreConstants;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.tool.Tool;
import cd.lan1akea.core.tool.ToolCallContext;
import cd.lan1akea.core.tool.ToolResult;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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

    /**
     * 设置当前消息列表。
     *
     * @param messages 消息列表
     */
    public void setMessages(List<Msg> messages) {
        setPayload(CoreConstants.EventPayload.MESSAGES, messages);
    }

    /**
     * @return 消息列表，可能为 null
     */
    @SuppressWarnings("unchecked")
    public List<Msg> getMessages() {
        return getPayload(CoreConstants.EventPayload.MESSAGES);
    }

    /**
     * 设置绕过模型调用的直接回复消息。
     * 非 null 时调用方跳过模型直接返回此消息。
     *
     * @param msg 直接回复消息
     */
    public void setBypassMessage(Msg msg) {
        setPayload(CoreConstants.EventPayload.BYPASS_MESSAGE, msg);
    }

    /**
     * @return 绕过消息，null 表示正常走模型
     */
    public Msg getBypassMessage() {
        return getPayload(CoreConstants.EventPayload.BYPASS_MESSAGE);
    }

    /**
     * @return 工具实例，可能为 null
     */
    public Tool getTool() {
        return getPayload(CoreConstants.EventPayload.TOOL);
    }

    /**
     * 设置工具实例。
     *
     * @param tool 工具实例
     */
    public void setTool(Tool tool) {
        setPayload(CoreConstants.EventPayload.TOOL, tool);
    }

    /**
     * @return 工具调用上下文，可能为 null
     */
    public ToolCallContext getCallParam() {
        return getPayload(CoreConstants.EventPayload.CALL_PARAM);
    }

    /**
     * 设置工具调用上下文。
     *
     * @param callParam 工具调用上下文
     */
    public void setCallParam(ToolCallContext callParam) {
        setPayload(CoreConstants.EventPayload.CALL_PARAM, callParam);
    }

    /**
     * @return 工具执行结果，可能为 null
     */
    public ToolResult getResult() {
        return getPayload(CoreConstants.EventPayload.RESULT);
    }

    /**
     * 设置工具执行结果。
     *
     * @param result 工具执行结果
     */
    public void setResult(ToolResult result) {
        setPayload(CoreConstants.EventPayload.RESULT, result);
    }
}
