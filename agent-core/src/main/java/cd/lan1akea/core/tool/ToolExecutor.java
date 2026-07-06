package cd.lan1akea.core.tool;

import cd.lan1akea.core.CoreConstants.Defaults;
import cd.lan1akea.core.CoreConstants.UI;
import cd.lan1akea.core.exception.HumanInterventionException;
import cd.lan1akea.core.exception.ToolExecutionException;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 工具执行器。
 * 完整的工具执行流程：查找 -> 权限校验 -> 参数验证 -> 审批检查 -> 执行 -> 超时控制。
 */
public class ToolExecutor {

    /**
     * 工具注册表，用于查找工具
     */
    private final ToolRegistry registry;
    /**
     * 工具事件发射器
     */
    private final ToolEmitter emitter;
    /**
     * 工具参数校验器
     */
    private final ToolValidator toolValidator;
    /**
     * 使用默认值创建工具执行器。
     *
     * @param registry 工具注册表
     */
    public ToolExecutor(ToolRegistry registry) {
        this(registry, new DefaultToolEmitter(), new ToolValidator());
    }

    /**
     * 使用自定义发射器和校验器创建工具执行器。
     *
     * @param registry      工具注册表
     * @param emitter       工具事件发射器
     * @param toolValidator 工具校验器
     */
    public ToolExecutor(ToolRegistry registry, ToolEmitter emitter,
                         ToolValidator toolValidator) {
        this.registry = registry;
        this.emitter = emitter;
        this.toolValidator = toolValidator != null ? toolValidator : new ToolValidator();
    }

    /**
     * 执行工具调用，完整的执行链：
     * 1. 查找工具（ToolRegistry）
     * 2. 参数验证（ToolValidator）
     * 3. 执行前事件
     * 4. 执行工具（带超时控制）
     *
     * @param callParam 调用参数
     * @return Mono<ToolResult> 执行结果
     */
    public Mono<ToolResult> execute(ToolCallContext callParam) {
        return doExecute(callParam);
    }

    /**
     * 执行工具调用（租户感知）。
     * @deprecated 使用 execute(ToolCallContext)，上下文已内嵌在 callParam 中
     */
    @Deprecated
    public Mono<ToolResult> execute(ToolCallContext callParam, String tenantId) {
        return doExecute(callParam);
    }

    /**
     * 执行工具调用流程：
     * 1. 查找工具（ToolRegistry）
     * 2. 参数验证（ToolValidator）
     * 3. 执行前事件
     * 4. 执行工具（带超时控制）
     *
     * @param callParam 工具调用上下文
     * @return 执行结果 Mono
     */
    @SuppressWarnings("deprecation")
    private Mono<ToolResult> doExecute(ToolCallContext callParam) {
        return Mono.defer(() -> {
            // 1. 查找工具
            String tid = callParam.getTenantId();
            Tool tool = registry.getForContext(
                tid, callParam.getUserId(),
                callParam.getSessionId(), callParam.getToolName());
            if (tool == null) {
                String ctx = tid != null ? tid : Defaults.CONTEXT_GLOBAL;
                return Mono.just(ToolResult.failure(
                    UI.TOOL_NOT_FOUND + callParam.getToolName()
                    + UI.TOOL_CTX_UNAVAILABLE + ctx + UI.TOOL_CTX_UNAVAILABLE_SUFFIX));
            }


            // 2. 参数验证
            try {
                toolValidator.validate(tool.getParameters(), callParam);
            } catch (IllegalArgumentException e) {
                return Mono.just(ToolResult.failure(UI.TOOL_PARAM_INVALID + e.getMessage()));
            }

            // 3. 执行前事件
            emitter.beforeExecute(tool, callParam);

            // 4. 执行工具，带超时控制和异常处理
            Mono<ToolResult> execution = tool.execute(callParam)
                .onErrorResume(e -> {
                    emitter.onError(tool, callParam, e);
                    if (e instanceof HumanInterventionException) {
                        return Mono.error(e); // 不吞掉，抛给 ReActLoop 处理
                    }
                    return Mono.just(ToolResult.failure(
                        UI.TOOL_EXEC_ERROR + e.getMessage()));
                });

            // 超时控制
            long timeoutMs = tool.getTimeoutMs();
            if (timeoutMs > 0) {
                execution = execution.timeout(Duration.ofMillis(timeoutMs),
                    Mono.just(ToolResult.failure(UI.TOOL_TIMEOUT + timeoutMs + UI.TOOL_TIMEOUT_SUFFIX))
                        .doOnNext(r -> emitter.onError(tool, callParam,
                            new ToolExecutionException(tool.getName(), "执行超时"))));
            }

            return execution
                .doOnNext(result -> emitter.afterExecute(tool, callParam, result))
                .map(r -> r.withCallId(callParam.getCallId()));
        });
    }

    /**
     * 返回工具注册表。
     *
     * @return 工具注册表
     */
    public ToolRegistry getRegistry() { return registry; }
}
