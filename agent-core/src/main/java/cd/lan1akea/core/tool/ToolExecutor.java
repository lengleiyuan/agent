package cd.lan1akea.core.tool;

import cd.lan1akea.core.exception.ToolExecutionException;
import java.util.List;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 工具执行器。
 * <p>
 * 完整的工具执行流程：查找 → 权限校验 → 参数验证 → 审批检查 → 执行 → 超时控制。
 * </p>
 */
public class ToolExecutor {

    private final ToolRegistry registry;
    private final ToolEmitter emitter;
    private final ToolValidator toolValidator;

    public ToolExecutor(ToolRegistry registry) {
        this(registry, new DefaultToolEmitter(), new ToolValidator());
    }

    public ToolExecutor(ToolRegistry registry, ToolEmitter emitter,
                         ToolValidator toolValidator) {
        this.registry = registry;
        this.emitter = emitter;
        this.toolValidator = toolValidator != null ? toolValidator : new ToolValidator();
    }

    /**
     * 执行工具调用，完整的执行链：
     * <ol>
     * <li>查找工具（ToolRegistry）</li>
     * <li>权限校验（PermissionEngine）</li>
     * <li>参数验证（ToolValidator）</li>
     * <li>审批检查（requiresApproval → ToolSuspendException）</li>
     * <li>执行工具（带超时控制）</li>
     * </ol>
     *
     * @param callParam 调用参数
     * @return Mono&lt;ToolResult&gt; 执行结果
     */
    public Mono<ToolResult> execute(ToolCallParam callParam) {
        return execute(callParam, callParam.getTenantId());
    }

    /**
     * 执行工具调用（租户感知）。
     * @deprecated 使用 {@link #execute(ToolCallParam)}，上下文已内嵌在 callParam 中
     */
    @Deprecated
    public Mono<ToolResult> execute(ToolCallParam callParam, String tenantId) {
        return Mono.defer(() -> {
            // 1. 查找工具（优先用 callParam 中的上下文，fallback 到显式传参）
            String tid = callParam.getTenantId() != null ? callParam.getTenantId() : tenantId;
            Tool tool = registry.getForContext(
                tid, callParam.getUserId(),
                callParam.getSessionId(), callParam.getToolName());
            if (tool == null) {
                String ctx = tid != null ? tid : "global";
                return Mono.just(ToolResult.failure(
                    "工具不存在: " + callParam.getToolName()
                    + "。上下文 [" + ctx + "] 中不可用"));
            }

            // 2. 权限校验（通过 PermissionEngine 在 ReActLoop Hook 层完成）

            // 3. 参数验证
            try {
                toolValidator.validate(tool.getParameters(), callParam);
            } catch (IllegalArgumentException e) {
                return Mono.just(ToolResult.failure("参数校验失败: " + e.getMessage()));
            }

            // 4. 审批检查
            if (tool.requiresApproval()) {
                return Mono.error(new ToolSuspendException(
                    tool.getName(), "工具 [" + tool.getName() + "] 需要人工审批后才能执行"));
            }

            // 5. 执行前事件
            emitter.beforeExecute(tool, callParam);

            // 6. 执行工具，带超时控制和异常处理
            Mono<ToolResult> execution = tool.execute(callParam)
                .onErrorResume(e -> {
                    emitter.onError(tool, callParam, e);
                    if (e instanceof ToolSuspendException) {
                        return Mono.error(e); // 不吞掉暂停异常，抛给 ReActLoop 的 InterruptHook
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
