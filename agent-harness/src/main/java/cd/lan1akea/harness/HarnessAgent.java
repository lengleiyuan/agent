package cd.lan1akea.harness;

import cd.lan1akea.core.agent.AbstractAgent;
import cd.lan1akea.core.agent.Agent;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.model.ChatResponse;
import cd.lan1akea.core.model.ChatStreamChunk;
import cd.lan1akea.core.model.GenerateOptions;
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
 *     .build()
 *     .block(); // 阻塞等待构建完成
 *
 * ChatResponse response = agent.chat(messages, null).block();
 * </pre>
 * </p>
 */
public class HarnessAgent implements Agent {

    private final AbstractAgent delegate;

    HarnessAgent(AbstractAgent delegate) {
        this.delegate = delegate;
    }

    /**
     * 创建 Builder。
     */
    public static HarnessAgentBuilder builder() {
        return new HarnessAgentBuilder();
    }

    @Override
    public String getName() { return delegate.getName(); }

    @Override
    public Mono<ChatResponse> chat(List<Msg> messages, GenerateOptions options) {
        return delegate.chat(messages, options);
    }

    @Override
    public Flux<ChatStreamChunk> stream(List<Msg> messages, GenerateOptions options) {
        return delegate.stream(messages, options);
    }

    /**
     * 优雅关闭。
     */
    public Mono<Void> shutdown() { return delegate.shutdown(); }

    /** @return 内部 Agent 实例 */
    public AbstractAgent getDelegate() { return delegate; }
}
