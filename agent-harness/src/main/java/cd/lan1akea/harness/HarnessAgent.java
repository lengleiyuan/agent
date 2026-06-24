package cd.lan1akea.harness;

import cd.lan1akea.core.agent.ReActAgent;
import cd.lan1akea.core.agent.CallableAgent;
import cd.lan1akea.core.agent.ObservableAgent;
import cd.lan1akea.core.agent.StreamableAgent;
import cd.lan1akea.core.context.RuntimeContext;
import cd.lan1akea.core.event.DomainEvent;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.model.ChatResponse;
import cd.lan1akea.core.model.ChatStreamChunk;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * HarnessAgent SDK 门面类。
 * <p>
 * 对外唯一入口，封装 Agent 构建和调用的完整生命周期。
 * 用法：
 * <pre>
* HarnessAgent agent = HarnessAgent.builder()
 *     .name("MyAgent")
 *     .model(new OpenAIChatModel(apiKey, "gpt-4o"))
 *     .tool(new CalculatorTool())
 *     .build();
 *
 * ChatResponse response = agent.chat(messages).block();
 * </pre>
 * </p>
 */
public class HarnessAgent implements ObservableAgent, StreamableAgent, CallableAgent {

    private final ReActAgent delegate;

    public HarnessAgent(ReActAgent delegate) {
        this.delegate = delegate;
    }


    public static HarnessAgentBuilder builder() {
        return new HarnessAgentBuilder();
    }

    // ========================================================================
    // Agent
    // ========================================================================

    @Override
    public String getName() { return delegate.getName(); }

    @Override
    public String getId() { return delegate.getId(); }

    @Override
    public void interrupt() { delegate.interrupt(); }

    @Override
    public void interrupt(Msg feedbackMsg) { delegate.interrupt(feedbackMsg); }

    // ========================================================================
    // CallableAgent
    // ========================================================================

    @Override
    public Mono<ChatResponse> chat(List<Msg> messages) {
        return delegate.chat(messages);
    }

    @Override
    public Mono<ChatResponse> chat(List<Msg> messages, RuntimeContext ctx) {
        return delegate.chat(messages, ctx);
    }

    @Override
    public Mono<ChatResponse> chat(List<Msg> messages, Class<?> outputClass) {
        return delegate.chat(messages, outputClass);
    }

    // ========================================================================
    // StreamableAgent
    // ========================================================================

    @Override
    public Flux<ChatStreamChunk> stream(List<Msg> messages) {
        return delegate.stream(messages);
    }

    @Override
    public Flux<ChatStreamChunk> stream(List<Msg> messages, RuntimeContext ctx) {
        return delegate.stream(messages, ctx);
    }

    @Override
    public Flux<ChatStreamChunk> stream(List<Msg> messages, Class<?> outputClass) {
        return delegate.stream(messages, outputClass);
    }

    // ========================================================================
    // ObservableAgent
    // ========================================================================

    @Override
    public Mono<Void> observe(Msg message) { return delegate.observe(message); }

    @Override
    public Flux<DomainEvent> events() { return delegate.events(); }

    @Override
    public Flux<DomainEvent> events(String eventType) { return delegate.events(eventType); }

    // ========================================================================
    // Harness
    // ========================================================================

    public Mono<Void> shutdown() { return delegate.shutdown(); }

    public ReActAgent getDelegate() { return delegate; }
}
