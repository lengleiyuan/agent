package cd.lan1akea.core.model;

import cd.lan1akea.core.message.Msg;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 聊天模型接口。
 * <p>
 * 定义与 LLM 对话的标准接口，支持同步（单次）和流式两种模式。
 * </p>
 */
public interface ChatModel extends Model {

    /**
     * 单次聊天调用。
     *
     * @param messages 消息列表（包含系统提示、历史、当前用户消息）
     * @param options  生成选项（温度、最大Token等）
     * @return Mono&lt;ChatResponse&gt; 聊天响应
     */
    Mono<ChatResponse> chat(List<Msg> messages, GenerateOptions options);

    /**
     * 流式聊天调用。
     *
     * @param messages 消息列表
     * @param options  生成选项
     * @return Flux&lt;ChatStreamChunk&gt; 流式响应块流
     */
    Flux<ChatStreamChunk> stream(List<Msg> messages, GenerateOptions options);

    /**
     * 带工具 Schema 的聊天调用。
     *
     * @param messages   消息列表
     * @param toolSchemas 可用工具 Schema 列表
     * @param options    生成选项
     * @return Mono&lt;ChatResponse&gt; 聊天响应
     */
    Mono<ChatResponse> chatWithTools(List<Msg> messages, List<ToolSchema> toolSchemas, GenerateOptions options);

    /**
     * 是否支持流式调用。
     */
    default boolean supportsStreaming() {
        return true;
    }

    /**
     * 是否支持工具调用。
     */
    default boolean supportsToolCalling() {
        return true;
    }
}
