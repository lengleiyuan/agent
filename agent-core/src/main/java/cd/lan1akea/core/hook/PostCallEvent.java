package cd.lan1akea.core.hook;

import cd.lan1akea.core.model.ChatResponse;
import cd.lan1akea.core.model.ChatStreamChunk;

import java.util.List;

/**
 * 调用后事件。
 * <p>
 * 携带本次调用的结果。Hook 可读写结果用于广播、审计等。
 * </p>
 */
public class PostCallEvent extends HookEvent {

    public PostCallEvent() {
        super(HookEventType.POST_CALL);
    }

    /** @return chat 结果（流式模式下为 null） */
    public ChatResponse getChatResponse() {
        return getPayload("chatResponse");
    }

    /** 设置 chat 结果 */
    public void setChatResponse(ChatResponse response) {
        setPayload("chatResponse", response);
    }

    /** @return 流式 chunks（非流式模式下为 null） */
    @SuppressWarnings("unchecked")
    public List<ChatStreamChunk> getStreamChunks() {
        return getPayload("streamChunks");
    }

    /** 设置流式 chunks */
    public void setStreamChunks(List<ChatStreamChunk> chunks) {
        setPayload("streamChunks", chunks);
    }

    /** @return 是否流式调用 */
    public boolean isStream() {
        Boolean s = getPayload("stream");
        return s != null && s;
    }

    /** 标记为流式调用 */
    public void setStream(boolean stream) {
        setPayload("stream", stream);
    }
}
