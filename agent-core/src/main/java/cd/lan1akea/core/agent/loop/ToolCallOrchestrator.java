package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.CoreConstants.EventPayload;
import cd.lan1akea.core.CoreConstants.UI;
import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.message.ToolUseBlock;
import cd.lan1akea.core.tool.*;

import java.util.List;
import reactor.core.publisher.Mono;

/**
 * 工具调用编排器。
 *
 * <p>将单次工具调用拆为四个独立步骤：
 * <ol>
 *   <li>构建上下文 —— 从 ToolUseBlock + LoopContext 组装 ToolCallContext</li>
 *   <li>PRE Hook —— 分发 PRE_TOOL_CALL 事件，处理 abort/skip/continue</li>
 *   <li>执行 —— AroundHook 洋葱包裹 toolExecutor，介入异常穿透</li>
 *   <li>POST Hook —— 分发 POST_TOOL_CALL 事件</li>
 * </ol>
 *
 * <p>人工介入异常不在此处理，直接穿透到 LoopExecutor 统一 catch。
 */
public class ToolCallOrchestrator {

    /** 工具执行器 */
    private final ToolExecutor toolExecutor;
    /** 工具注册表 */
    private final ToolRegistry toolRegistry;
    /** Hook 分发器 */
    private final HookDispatcher hookDispatcher;
    /** AroundHook 链 */
    private final AroundHookChain aroundHookChain;

    /**
     * 构建工具调用编排器。
     *
     * @param toolExecutor    工具执行器
     * @param toolRegistry    工具注册表
     * @param hookDispatcher  Hook 分发器
     * @param aroundHookChain AroundHook 链
     */
    public ToolCallOrchestrator(ToolExecutor toolExecutor, ToolRegistry toolRegistry,
                                 HookDispatcher hookDispatcher, AroundHookChain aroundHookChain) {
        this.toolExecutor = toolExecutor;
        this.toolRegistry = toolRegistry;
        this.hookDispatcher = hookDispatcher;
        this.aroundHookChain = aroundHookChain;
    }

    /**
     * 执行单个工具调用，返回含 callId 的结果。
     *
     * <p>标准流程：构建上下文 → PRE Hook（abort/skip/continue）→ 执行 → POST Hook。
     *
     * @param tc  模型请求的工具调用块
     * @param ctx 循环上下文
     * @return 含 callId 的工具执行结果
     */
    public Mono<ToolResult> execute(ToolUseBlock tc, LoopContext ctx) {
        HookContext hc = ctx.toHookContext();
        ToolCallContext param = buildContext(tc, ctx);
        ToolCallEvent event = new ToolCallEvent(HookEventType.PRE_TOOL_CALL, param);
        event.setTool(toolRegistry.getForContext(
                ctx.getTenantId(), ctx.getUserId(), ctx.getSessionId(), tc.getName()));

        return dispatchPreHook(event, hc)
                .switchIfEmpty(
                        executeWithApproval(param, event, hc, ctx)
                                .flatMap(result -> dispatchPostHook(param, result, hc)))
                .map(r -> r.withCallId(param.getCallId()));
    }

    /**
     * 直接执行工具调用，跳过 PRE Hook 和审批流程。
     *
     * <p>用于人工介入恢复场景。当介入被批准或澄清后，需要以原参数
     * （或修正参数）重新执行工具调用。此时 ToolCallContext 已被标记为
     * approved，不再需要经过审批 Hook，直接进入 AroundHookChain 执行。
     *
     * <p>与 {@link #execute(ToolUseBlock, LoopContext)} 的区别：
     * <ul>
     *   <li>不调用 dispatchPreHook（跳过审批）</li>
     *   <li>参数已预先构建（非从 ToolUseBlock 解析）</li>
     *   <li>仍会经过 AroundHookChain 和 POST Hook</li>
     * </ul>
     *
     * @param param 预先构建的工具调用上下文（标记为 approved）
     * @param ctx   循环上下文
     * @return 工具执行结果的 Mono
     */
    public Mono<ToolResult> executeDirect(ToolCallContext param, LoopContext ctx) {
        HookContext hc = ctx.toHookContext();
        ToolCallEvent event = new ToolCallEvent(HookEventType.PRE_TOOL_CALL, param);
        event.setTool(toolRegistry.getForContext(
                ctx.getTenantId(), ctx.getUserId(), ctx.getSessionId(), param.getToolName()));

        return executeWithApproval(param, event, hc, ctx)
                .flatMap(result -> dispatchPostHook(param, result, hc))
                .map(r -> r.withCallId(param.getCallId()));
    }

    /**
     * 步骤一：从 ToolUseBlock 和 LoopContext 构建 ToolCallContext。
     *
     * @param tc  模型请求的工具调用块
     * @param ctx 循环上下文
     * @return 填充了多租户身份信息的工具调用上下文
     */
    private ToolCallContext buildContext(ToolUseBlock tc, LoopContext ctx) {
        return ToolCallContext.builder()
                .callId(tc.getId())
                .toolName(tc.getName())
                .arguments(tc.getArgumentsMap())
                .tenantId(ctx.getTenantId())
                .userId(ctx.getUserId())
                .sessionId(ctx.getSessionId())
                .attributes(ctx.getAttributes())
                .build();
    }

    /**
     * 步骤二：分发 PRE_TOOL_CALL Hook。
     *
     * <p>处理三种结果：abort（终止）、skip（跳过）、continue（继续执行）。
     *
     * @param event 工具调用事件
     * @param hc    Hook 上下文
     * @return 终止时返回结果 Mono，继续时返回 Mono.empty()
     */
    private Mono<ToolResult> dispatchPreHook(ToolCallEvent event, HookContext hc) {
        return hookDispatcher.dispatch(event, hc)
                .flatMap(r -> {
                    if (r.isAbort()) {
                        return Mono.just(ToolResult.failure(UI.TOOL_BLOCKED + r.getAbortReason()));
                    }
                    if (r.isSkip()) {
                        ToolResult skipped = ToolResult.success(
                                UI.TOOL_SKIPPED_PREFIX
                                        + (r.getSkipReason() != null ? r.getSkipReason() : UI.TOOL_SKIPPED_DEFAULT));
                        ToolCallEvent postSkip = new ToolCallEvent(HookEventType.POST_TOOL_CALL,
                                event.getCallParam(), skipped);
                        return hookDispatcher.dispatch(postSkip, hc).thenReturn(skipped);
                    }
                    return Mono.empty(); // continue
                });
    }

    /**
     * 步骤三：通过 AroundHook 链执行工具。
     *
     * <p>介入异常（HumanInterventionException）不在此捕获，
     * 直接穿透到 LoopExecutor.executeAct() 的 onErrorResume。
     *
     * @param param 工具调用上下文
     * @param event 工具调用事件
     * @param hc    Hook 上下文
     * @param ctx   循环上下文
     * @return 工具执行结果
     */
    private Mono<ToolResult> executeWithApproval(ToolCallContext param, ToolCallEvent event,
                                                   HookContext hc, LoopContext ctx) {
        return aroundHookChain.aroundToolCall(event, hc,
                        (HookEvent e) -> toolExecutor.execute(param)
                                .map(result -> {
                                e.setPayload(EventPayload.TOOL_RESULT, result);
                                    ((ToolCallEvent) e).setResult(result);
                                    return e;
                                }))
                .flatMap(e -> Mono.justOrEmpty((ToolResult) e.getPayload(EventPayload.TOOL_RESULT)));
    }

    /**
     * 步骤四：分发 POST_TOOL_CALL Hook。
     *
     * <p>无论工具执行成功或失败，都会触发 POST Hook。Hook 结果不影响返回值。
     *
     * @param param  工具调用上下文
     * @param result 工具执行结果
     * @param hc     Hook 上下文
     * @return 原始执行结果
     */
    private Mono<ToolResult> dispatchPostHook(ToolCallContext param, ToolResult result, HookContext hc) {
        ToolCallEvent post = new ToolCallEvent(HookEventType.POST_TOOL_CALL, param, result);
        return hookDispatcher.dispatch(post, hc).thenReturn(result);
    }

    /**
     * 从循环上下文构建 Hook 上下文。
     *
     * @param ctx 循环上下文
     * @return 新的 Hook 上下文
     */
}
