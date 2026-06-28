package cd.lan1akea.core.hook;

import cd.lan1akea.core.message.Msg;

import java.util.Collections;
import java.util.List;

/**
 * 推理事件。
 * 在 PRE_REASONING 或 POST_REASONING 阶段携带消息列表和生成选项。
 */
public class ReasoningEvent extends HookEvent {

    /**
     * 创建指定事件类型的推理事件。
     */
    public ReasoningEvent(HookEventType eventType) {
        super(eventType);
    }

    /**
     * 设置当前消息列表
     */
    public void setMessages(List<Msg> messages) {
        setPayload("messages", messages);
    }

    /**
     * 获取消息列表
     */
    @SuppressWarnings("unchecked")
    public List<Msg> getMessages() {
        List<Msg> msgs = getPayload("messages");
        return msgs != null ? msgs : Collections.emptyList();
    }

    /**
     * 设置绕过模型调用的直接回复消息。
     * 非 null 时 ReActLoop 将跳过模型，直接返回此消息。
     */
    public void setBypassMessage(Msg msg) {
        setPayload("bypassMessage", msg);
    }

    /**
     * @return 绕过消息，null 表示正常走模型
     */
    public Msg getBypassMessage() {
        return getPayload("bypassMessage");
    }
}
