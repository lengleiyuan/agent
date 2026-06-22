package cd.lan1akea.core.tool;

import cd.lan1akea.core.exception.ToolExecutionException;
import cd.lan1akea.core.tenant.PermissionDecision;
import cd.lan1akea.core.tenant.PermissionEngine;
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
    private final PermissionEngine permissionEngine;
    private final ToolExecutionContextProvider contextProvider;

    public ToolExecutor(ToolRegistry registry) {
        this(registry, new DefaultToolEmitter(), new ToolValidator(), null, null);
    }

    public ToolExecutor(ToolRegistry registry, ToolEmitter emitter,
                         ToolValidator toolValidator, PermissionEngine permissionEngine,
                         ToolExecutionContextProvider contextProvider) {
        this.registry = registry;
        this.emitter = emitter;
        this.toolValidator = toolValidator != null ? toolValidator : new ToolValidator();
        this.permissionEngine = permissionEngine;
        this.contextProvider = contextProvider;
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
        return Mono.defer(() -> {
            // 1. 查找工具
            Tool tool = registry.get(callParam.getToolName());
            if (tool == null) {
                return Mono.just(ToolResult.failure(
                    "工具不存在: " + callParam.getToolName()
                    + "。可用工具: " + String.join(", ", registry.getToolNames())));
            }

            // 2. 权限校验
            if (permissionEngine != null && contextProvider != null) {
                ToolExecutionContext ctx = contextProvider.getContext();
                if (ctx != null) {
                    cd.lan1akea.core.tenant.User user = new cd.lan1akea.core.tenant.User(
                        new cd.lan1akea.core.tenant.UserId(
                            ctx.getUserId() != null ? Long.parseLong(ctx.getUserId()) : 0),
                        ctx.getTenantId() != null ? Long.parseLong(ctx.getTenantId()) : 0,
                        "agent-user", "ACTIVE",
                        java.util.Collections.emptyList(),
                        java.time.LocalDateTime.now());
                    PermissionDecision decision = permissionEngine.evaluate(
                        user, cd.lan1akea.core.tenant.ResourceType.TOOL, "execute");
                    if (decision.isDenied()) {
                        return Mono.just(ToolResult.failure(
                            "权限不足: " + decision.getReason()));
                    }
                    if (decision.isAsk()) {
                        // 需要审批 → 抛出暂停异常
                        return Mono.error(new ToolSuspendException(
                            tool.getName(), "工具 [" + tool.getName() + "] 需要审批: " + decision.getReason()));
                    }
                }
            }

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
