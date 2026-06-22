package cd.lan1akea.core.agent;

import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.model.ChatResponse;

import java.util.List;

/**
 * 同步调用 Agent 接口。
 */
public interface CallableAgent extends Agent {

    /** 阻塞式单次对话（仅在非响应式上下文中使用） */
    default ChatResponse call(List<Msg> messages) {
        return chat(messages).block();
    }
}
