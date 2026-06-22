package cd.lan1akea.core.middleware;

import cd.lan1akea.core.agent.loop.LoopContext;
import cd.lan1akea.core.model.ChatResponse;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 重试中间件。
 * <p>
 * 在核心调用失败时按配置重试。
 * </p>
 */
public class RetryMiddleware implements Middleware {

    private final int maxRetries;
    private final long delayMs;

    public RetryMiddleware(int maxRetries, long delayMs) {
        this.maxRetries = maxRetries;
        this.delayMs = delayMs;
    }

    @Override
    public Mono<ChatResponse> after(ChatResponse response) {
        // 此处仅为示例钩子，实际重试逻辑在 ChatModelBase 中
        return Mono.just(response);
    }
}
