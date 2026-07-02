package cd.lan1akea.core.model;

import cd.lan1akea.core.exception.ModelCallException;
import cd.lan1akea.core.message.Msg;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ChatModel 熔断装饰器。
 * 包装任意 ChatModel，在连续失败达到阈值时熔断，避免雪崩。
 *
 * <p>状态机：</p>
 * <pre>
 * CLOSED ──(失败次数达阈值)──▶ OPEN ──(等待 halfOpenAfterMs)──▶ HALF_OPEN
 * HALF_OPEN ──(试探成功)──▶ CLOSED
 * HALF_OPEN ──(试探失败)──▶ OPEN
 * </pre>
 *
 * <p>OPEN 状态下所有请求直接失败，不穿透到下游模型服务。</p>
 */
public class ResilienceChatModel implements ChatModel {

    /**
     * 被装饰的聊天模型
     */
    private final ChatModel delegate;
    /**
     * 触发熔断的连续失败阈值
     */
    private final int failureThreshold;
    /**
     * 熔断后半开试探前的等待时间（毫秒）
     */
    private final long halfOpenAfterMs;

    /**
     * 当前熔断状态
     */
    private final AtomicReference<CircuitState> state;
    /**
     * 当前窗口内的连续失败计数
     */
    private final AtomicInteger failureCount;
    /**
     * 熔断打开的时间戳
     */
    private final AtomicLong openedAt;

    /**
     * 创建带默认参数的熔断装饰器（5 次失败触发，30 秒后半开）。
     *
     * @param delegate 被装饰的聊天模型
     */
    public ResilienceChatModel(ChatModel delegate) {
        this(delegate, 5, 30_000);
    }

    /**
     * 创建熔断装饰器。
     *
     * @param delegate          被装饰的聊天模型
     * @param failureThreshold  触发熔断的连续失败阈值
     * @param halfOpenAfterMs   熔断后半开试探前的等待时间（毫秒）
     */
    public ResilienceChatModel(ChatModel delegate, int failureThreshold, long halfOpenAfterMs) {
        this.delegate = delegate;
        this.failureThreshold = failureThreshold;
        this.halfOpenAfterMs = halfOpenAfterMs;
        this.state = new AtomicReference<>(CircuitState.CLOSED);
        this.failureCount = new AtomicInteger(0);
        this.openedAt = new AtomicLong(0);
    }

    @Override
    public String getProvider() { return delegate.getProvider(); }

    @Override
    public String getModelName() { return delegate.getModelName(); }

    @Override
    public int getMaxInputTokens() { return delegate.getMaxInputTokens(); }

    @Override
    public int getDefaultMaxTokens() { return delegate.getDefaultMaxTokens(); }

    @Override
    public double getDefaultTemperature() { return delegate.getDefaultTemperature(); }

    @Override
    public boolean supportsStreaming() { return delegate.supportsStreaming(); }

    @Override
    public Mono<ChatResponse> chat(List<Msg> messages, GenerateOptions options) {
        return delegate.chat(messages, options);
    }

    @Override
    public Flux<ChatStreamChunk> stream(List<Msg> messages, GenerateOptions options) {
        return delegate.stream(messages, options);
    }

    @Override
    public Flux<ChatStreamChunk> streamWithTools(List<Msg> messages,
                                                  List<ToolSchema> toolSchemas,
                                                  GenerateOptions options) {
        return delegate.streamWithTools(messages, toolSchemas, options);
    }

    @Override
    public Mono<ChatResponse> chatWithTools(List<Msg> messages,
                                             List<ToolSchema> toolSchemas,
                                             GenerateOptions options) {
        CircuitState cs = resolveState();
        if (cs == CircuitState.OPEN) {
            return Mono.error(new ModelCallException(getProvider(), getModelName(),
                "熔断器已打开，拒绝请求（连续失败 " + failureCount.get() + " 次）"));
        }
        if (cs == CircuitState.HALF_OPEN) {
            return delegate.chatWithTools(messages, toolSchemas, options)
                .map(resp -> {
                    onSuccess();
                    return resp;
                })
                .onErrorResume(e -> {
                    onError(e);
                    return Mono.error(e);
                });
        }
        return delegate.chatWithTools(messages, toolSchemas, options)
            .map(resp -> {
                onSuccess();
                return resp;
            })
            .onErrorResume(e -> {
                onError(e);
                return Mono.error(e);
            });
    }

    /**
     * 解析当前熔断状态，检查是否需要从 OPEN 过渡到 HALF_OPEN。
     */
    private CircuitState resolveState() {
        CircuitState cs = state.get();
        if (cs == CircuitState.OPEN) {
            long elapsed = System.currentTimeMillis() - openedAt.get();
            if (elapsed >= halfOpenAfterMs) {
                state.compareAndSet(CircuitState.OPEN, CircuitState.HALF_OPEN);
                return state.get();
            }
        }
        return cs;
    }

    private void onSuccess() {
        failureCount.set(0);
        state.set(CircuitState.CLOSED);
    }

    private void onError(Throwable e) {
        int fc = failureCount.incrementAndGet();
        if (fc >= failureThreshold) {
            state.set(CircuitState.OPEN);
            openedAt.set(System.currentTimeMillis());
        }
    }

    /**
     * 获取当前熔断状态（用于监控/健康检查）。
     *
     * @return 当前电路状态
     */
    public CircuitState getCircuitState() {
        return resolveState();
    }

    /**
     * 获取当前失败计数。
     *
     * @return 连续失败次数
     */
    public int getFailureCount() { return failureCount.get(); }

    /**
     * 手动重置熔断器到 CLOSED 状态。
     */
    public void reset() {
        state.set(CircuitState.CLOSED);
        failureCount.set(0);
        openedAt.set(0);
    }

    /**
     * 熔断状态枚举。
     */
    public enum CircuitState {
        /**
         * 正常状态，请求正常通过
         */
        CLOSED,
        /**
         * 熔断打开，请求直接拒绝
         */
        OPEN,
        /**
         * 半开状态，允许少量请求试探
         */
        HALF_OPEN
    }
}
