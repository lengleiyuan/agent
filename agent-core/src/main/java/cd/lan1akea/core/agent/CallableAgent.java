package cd.lan1akea.core.agent;

import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.model.ChatResponse;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 同步调用 Agent 接口。
 * <p>
 * 提供阻塞式调用能力（底层仍为响应式，调用方使用 .block()）。
 * </p>
 */
public interface CallableAgent extends Agent {

    /**
     * 阻塞式单次对话。
     * <p>
     * 注意：仅在非响应式上下文中使用，响应式链路中请使用 chat()。
     * </p>
     *
     * @param messages 输入消息列表
     * @return ChatResponse（阻塞等待）
     */
    default ChatResponse call(List<Msg> messages) {
        return chat(messages, null).block();
    }
}
