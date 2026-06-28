package cd.lan1akea.core.model;

import cd.lan1akea.core.message.Msg;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 动态可替换的 ChatModel 委托包装器。
 * 通过 {@link #swap(ChatModel)} 运行时热切换模型，无需重启。
 */
public class DynamicChatModel implements ChatModel {

    private volatile ChatModel delegate;

    public DynamicChatModel(ChatModel initial) {
        this.delegate = initial;
    }

    public void swap(ChatModel newModel) {
        this.delegate = newModel;
    }

    public ChatModel getDelegate() {
        return delegate;
    }

    @Override public String getProvider() { return delegate.getProvider(); }
    @Override public String getModelName() { return delegate.getModelName(); }
    @Override public int getMaxInputTokens() { return delegate.getMaxInputTokens(); }
    @Override public int getDefaultMaxTokens() { return delegate.getDefaultMaxTokens(); }
    @Override public double getDefaultTemperature() { return delegate.getDefaultTemperature(); }

    @Override
    public Mono<ChatResponse> chat(List<Msg> messages, GenerateOptions options) {
        return delegate.chat(messages, options);
    }

    @Override
    public Flux<ChatStreamChunk> stream(List<Msg> messages, GenerateOptions options) {
        return delegate.stream(messages, options);
    }

    @Override
    public Mono<ChatResponse> chatWithTools(List<Msg> messages, List<ToolSchema> toolSchemas, GenerateOptions options) {
        return delegate.chatWithTools(messages, toolSchemas, options);
    }

    @Override
    public Flux<ChatStreamChunk> streamWithTools(List<Msg> messages, List<ToolSchema> toolSchemas, GenerateOptions options) {
        return delegate.streamWithTools(messages, toolSchemas, options);
    }
}
