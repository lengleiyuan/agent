package cd.lan1akea.core.hook;

import cd.lan1akea.core.message.Msg;

import java.util.Collections;
import java.util.List;

/**
 * 推理事件。
 * <p>
 * 在 PRE_REASONING 或 POST_REASONING 阶段携带消息列表和生成选项。
 * </p>
 */
public class ReasoningEvent extends HookEvent {

    public ReasoningEvent(HookEventType eventType) {
        super(eventType);
    }

    /** 设置当前消息列表 */
    public void setMessages(List<Msg> messages) {
        setPayload("messages", messages);
    }

    /** 获取消息列表 */
    @SuppressWarnings("unchecked")
    public List<Msg> getMessages() {
        List<Msg> msgs = getPayload("messages");
        return msgs != null ? msgs : Collections.emptyList();
    }
}
