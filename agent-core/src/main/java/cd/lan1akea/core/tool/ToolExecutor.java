package cd.lan1akea.core.tool;

import cd.lan1akea.core.exception.ToolExecutionException;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 工具执行器。
 * <p>
 * 负责工具调用的实际执行：查找工具、校验参数、执行、超时控制、异常处理。
 * </p>
 */
public class ToolExecutor {

    private final ToolRegistry registry;
    private final ToolEmitter emitter;

    public ToolExecutor(ToolRegistry registry) {
        this(registry, new DefaultToolEmitter());
    }

    public ToolExecutor(ToolRegistry registry, ToolEmitter emitter) {
        this.registry = registry;
        this.emitter = emitter;
    }

    /**
     * 执行工具调用。
     *
     * @param callParam 调用参数
     * @return Mono&lt;ToolResult&gt; 执行结果
     */
    public Mono<ToolResult> execute(ToolCallParam callParam) {
        return Mono.defer(() -> {
            // 查找工具
            Tool tool = registry.get(callParam.getToolName());
            if (tool == null) {
                return Mono.just(ToolResult.failure(
                    "工具不存在: " + callParam.getToolName() + "。可用工具: " + String.join(", ", registry.getToolNames())));
            }

            // 执行前事件
            emitter.beforeExecute(tool, callParam);

            // 执行工具，带超时控制
            Mono<ToolResult> execution = tool.execute(callParam)
                .onErrorResume(e -> {
                    emitter.onError(tool, callParam, e);
                    if (e instanceof ToolSuspendException) {
                        return Mono.error(e); // 不吞掉暂停异常
                    }
                    return Mono.just(ToolResult.failure(
                        "工具执行异常: " + e.getMessage()));
                });

            // 超时控制
            long timeoutMs = tool.getTimeoutMs();
            if (timeoutMs > 0) {
                execution = execution.timeout(Duration.ofMillis(timeoutMs),
                    Mono.just(ToolResult.failure("工具执行超时 (" + timeoutMs + "ms)"))
                        .doOnNext(r -> emitter.onError(tool, callParam,
                            new ToolExecutionException(tool.getName(), "执行超时"))));
            }

            return execution.doOnNext(result -> emitter.afterExecute(tool, callParam, result));
        });
    }

    /** @return 工具注册表 */
    public ToolRegistry getRegistry() { return registry; }
}
