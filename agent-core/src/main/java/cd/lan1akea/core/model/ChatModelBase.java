package cd.lan1akea.core.model;

import cd.lan1akea.core.exception.ModelCallException;
import cd.lan1akea.core.formatter.MessageFormatter;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.MsgRole;
import cd.lan1akea.core.message.AssistantMessage;
import cd.lan1akea.core.message.ToolUseBlock;
import cd.lan1akea.core.message.TextBlock;
import cd.lan1akea.core.util.JsonUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 聊天模型抽象基类。
 * <p>
 * 提供消息格式化、重试、结构化输出处理等共享逻辑。
 * 子类只需实现具体的 HTTP 调用细节。
 * </p>
 */
public abstract class ChatModelBase implements ChatModel {

    /** 提供商名称 */
    private final String provider;

    /** 模型名称 */
    private final String modelName;

    /** 消息格式化器 */
    private final MessageFormatter formatter;

    /** 最大重试次数 */
    private int maxRetries = 3;

    /** 重试间隔（毫秒） */
    private long retryDelayMs = 1000;

    protected ChatModelBase(String provider, String modelName, MessageFormatter formatter) {
        this.provider = provider;
        this.modelName = modelName;
        this.formatter = formatter;
    }

    @Override
    public String getProvider() { return provider; }

    @Override
    public String getModelName() { return modelName; }

    /**
     * 执行实际的 HTTP 调用（子类实现）。
     *
     * @param formattedMessages 格式化后的消息列表
     * @param toolSchemas       工具 Schema（可为空）
     * @param options           生成选项
     * @return Mono&lt;ChatResponse&gt;
     */
    protected abstract Mono<ChatResponse> doChat(List<Map<String, Object>> formattedMessages,
                                                  List<ToolSchema> toolSchemas,
                                                  GenerateOptions options);

    /**
     * 执行实际的流式 HTTP 调用（子类实现）。
     */
    protected abstract Flux<ChatStreamChunk> doStream(List<Map<String, Object>> formattedMessages,
                                                       List<ToolSchema> toolSchemas,
                                                       GenerateOptions options);

    @Override
    public Mono<ChatResponse> chat(List<Msg> messages, GenerateOptions options) {
        return chatWithTools(messages, Collections.emptyList(), options);
    }

    @Override
    public Flux<ChatStreamChunk> stream(List<Msg> messages, GenerateOptions options) {
        if (!supportsStreaming()) {
            return Flux.error(new ModelCallException(provider, modelName,
                "模型 [" + provider + ":" + modelName + "] 不支持流式调用"));
        }
        List<Map<String, Object>> formatted = formatter.format(messages);
        GenerateOptions opts = options != null ? options : GenerateOptions.defaults();
        return doStream(formatted, Collections.emptyList(), opts);
    }

    @Override
    public Mono<ChatResponse> chatWithTools(List<Msg> messages,
                                             List<ToolSchema> toolSchemas,
                                             GenerateOptions options) {
        List<Map<String, Object>> formatted = formatter.format(messages);
        GenerateOptions opts = options != null ? options : GenerateOptions.defaults();

        return doChatWithRetry(formatted, toolSchemas, opts, 0);
    }

    private Mono<ChatResponse> doChatWithRetry(List<Map<String, Object>> formatted,
                                                List<ToolSchema> toolSchemas,
                                                GenerateOptions options,
                                                int retryCount) {
        return doChat(formatted, toolSchemas, options)
            .onErrorResume(e -> {
                if (retryCount < maxRetries && isRetryable(e)) {
                    return Mono.delay(java.time.Duration.ofMillis(retryDelayMs * (retryCount + 1)))
                        .then(doChatWithRetry(formatted, toolSchemas, options, retryCount + 1));
                }
                return Mono.error(wrapException(e));
            });
    }

    /**
     * 判断异常是否可重试。
     */
    protected boolean isRetryable(Throwable e) {
        if (e instanceof ModelException) {
            int status = ((ModelException) e).getHttpStatus();
            return status == 429 || status >= 500;
        }
        return false;
    }

    /**
     * 将内部异常包装为框架标准异常。
     */
    protected ModelCallException wrapException(Throwable e) {
        if (e instanceof ModelCallException) {
            return (ModelCallException) e;
        }
        if (e instanceof ModelException) {
            ModelException me = (ModelException) e;
            return new ModelCallException(me.getProvider(), me.getModelName(),
                me.getHttpStatus(), me.getMessage(), e);
        }
        return new ModelCallException(provider, modelName, e.getMessage(), e);
    }

    // === 配置方法 ===

    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public void setRetryDelayMs(long retryDelayMs) { this.retryDelayMs = retryDelayMs; }

    protected MessageFormatter getFormatter() { return formatter; }
}
