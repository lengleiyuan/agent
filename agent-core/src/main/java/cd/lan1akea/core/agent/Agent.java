package cd.lan1akea.core.agent;

import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.model.ChatResponse;
import cd.lan1akea.core.model.ChatStreamChunk;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Agent 顶层接口。
 * <p>
 * Agent 构建时配置模型参数（温度、最大Token等），
 * 调用时只需传入消息列表。
 * </p>
 */
public interface Agent {

    String getName();

    /** 单次对话，使用 Agent 构建时配置的生成参数 */
    Mono<ChatResponse> chat(List<Msg> messages);

    /** 流式对话 */
    Flux<ChatStreamChunk> stream(List<Msg> messages);
}
