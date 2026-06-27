package cd.lan1akea.core.agent;

import cd.lan1akea.core.context.RuntimeContext;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.model.ChatStreamChunk;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 流式 Agent 接口。
 * 提供流式对话能力：stream。
 */
public interface StreamableAgent extends Agent {

    /**
     * 流式对话，使用默认上下文
     * */
    Flux<ChatStreamChunk> stream(List<Msg> messages);


    /**
     * 流式对话，携带运行时上下文
     * */
    Flux<ChatStreamChunk> stream(List<Msg> messages, RuntimeContext ctx);


    /**
     * 结构化输出流式对话
     * */
    Flux<ChatStreamChunk> stream(List<Msg> messages, Class<?> outputClass);


}
