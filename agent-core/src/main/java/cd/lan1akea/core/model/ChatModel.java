package cd.lan1akea.core.model;

import cd.lan1akea.core.message.Msg;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 聊天模型接口。
 * 定义与 LLM 对话的标准接口，支持同步（单次）和流式两种模式。
 */
public interface ChatModel {

    /**
     * @return 模型提供商名称
     */
    String getProvider();

    /**
     * @return 模型名称
     */
    String getModelName();

    /**
     * @return 模型最大输入 Token 数。默认 128K，
     *         具体模型可覆盖（如 gpt-4o-128k、claude-200k 等）。
     */
    default int getMaxInputTokens() { return 128_000; }

    /**
     * @return 默认最大输出 Token 数。
     */
    default int getDefaultMaxTokens() { return 4096; }

    /**
     * @return 默认温度。
     */
    default double getDefaultTemperature() { return 0.7; }

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
     * 带工具 Schema 的流式聊天调用。
     *
     * @param messages    消息列表
     * @param toolSchemas 可用工具 Schema 列表
     * @param options     生成选项
     * @return Flux&lt;ChatStreamChunk&gt; 流式响应块流
     */
    Flux<ChatStreamChunk> streamWithTools(List<Msg> messages, List<ToolSchema> toolSchemas, GenerateOptions options);

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
