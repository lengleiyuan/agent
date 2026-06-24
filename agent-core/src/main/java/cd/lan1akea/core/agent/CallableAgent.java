package cd.lan1akea.core.agent;

import cd.lan1akea.core.context.RuntimeContext;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.model.ChatResponse;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 同步调用 Agent 接口。
 * <p>
 * 提供非流式对话能力：chat / call。
 * </p>
 */
public interface CallableAgent extends Agent {

    /** 单次对话，使用默认上下文 */
    Mono<ChatResponse> chat(List<Msg> messages);

    /** 单次对话，携带运行时上下文（sessionId/tenantId/userId） */
    Mono<ChatResponse> chat(List<Msg> messages, RuntimeContext ctx);

    /** 结构化输出对话 — LLM 按指定 Java 类的 JSON Schema 格式输出 */
    Mono<ChatResponse> chat(List<Msg> messages, Class<?> outputClass);

    /** 阻塞式单次对话（仅在非响应式上下文中使用） */
    default ChatResponse call(List<Msg> messages) {
        return chat(messages).block();
    }
}
