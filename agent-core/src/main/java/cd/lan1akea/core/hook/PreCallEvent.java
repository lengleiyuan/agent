package cd.lan1akea.core.hook;

import cd.lan1akea.core.message.Msg;

import java.util.List;

/**
 * 调用前事件。
 * <p>
 * 携带本次调用的输入消息，Hook 可通过 getMessages() 修改输入（如注入系统提示）。
 * </p>
 */
public class PreCallEvent extends HookEvent {

    public PreCallEvent() {
        super(HookEventType.PRE_CALL);
    }

    /** @return 本次调用的输入消息列表（可变） */
    @SuppressWarnings("unchecked")
    public List<Msg> getMessages() {
        return getPayload("messages");
    }

    /** 设置输入消息 */
    public void setMessages(List<Msg> messages) {
        setPayload("messages", messages);
    }
}
