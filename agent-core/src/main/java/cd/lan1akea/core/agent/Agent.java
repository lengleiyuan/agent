package cd.lan1akea.core.agent;

import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.model.GenerateOptions;
import cd.lan1akea.core.model.ChatResponse;
import cd.lan1akea.core.model.ChatStreamChunk;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Agent 顶层接口。
 * <p>
 * 定义 Agent 的核心能力：单次对话（chat）、流式对话（stream）、获取名称。
 * 子接口扩展可观测、流式等能力。
 * </p>
 */
public interface Agent {

    /**
     * @return Agent 名称
     */
    String getName();

    /**
     * 单次对话。
     *
     * @param messages 输入消息列表
     * @param options  生成选项（可为 null 使用默认值）
     * @return Mono&lt;ChatResponse&gt; 助手响应
     */
    Mono<ChatResponse> chat(List<Msg> messages, GenerateOptions options);

    /**
     * 流式对话。
     *
     * @param messages 输入消息列表
     * @param options  生成选项（可为 null）
     * @return Flux&lt;ChatStreamChunk&gt; 流式响应块
     */
    Flux<ChatStreamChunk> stream(List<Msg> messages, GenerateOptions options);
}
