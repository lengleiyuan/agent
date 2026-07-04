package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.CoreConstants.EventPayload;
import cd.lan1akea.core.CoreConstants.UI;
import cd.lan1akea.core.approval.ApprovalStore;
import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.message.ToolUseBlock;
import cd.lan1akea.core.tool.*;

import java.util.List;
import reactor.core.publisher.Mono;

/**
 * 工具调用编排器。
 * 将单次工具调用拆为: 构建上下文 → PRE Hook → 审批/执行 → POST Hook。
 */
public class ToolCallOrchestrator {

    private final ToolExecutor toolExecutor;
    private final ToolRegistry toolRegistry;
    private final HookDispatcher hookDispatcher;
    private final AroundHookChain aroundHookChain;

    public ToolCallOrchestrator(ToolExecutor toolExecutor, ToolRegistry toolRegistry,
                                 HookDispatcher hookDispatcher, AroundHookChain aroundHookChain) {
        this.toolExecutor = toolExecutor;
        this.toolRegistry = toolRegistry;
        this.hookDispatcher = hookDispatcher;
        this.aroundHookChain = aroundHookChain;
    }

    /**
     * 执行单个工具调用，返回含 callId 的结果。
     */
    public Mono<ToolResult> execute(ToolUseBlock tc, LoopContext ctx) {
        HookContext hc = buildHookContext(ctx);
        ToolCallContext param = buildContext(tc, ctx);
        ToolCallEvent event = new ToolCallEvent(HookEventType.PRE_TOOL_CALL, param);
        event.setTool(toolRegistry.getForContext(
                ctx.getTenantId(), ctx.getUserId(), ctx.getSessionId(), tc.getName()));

        return dispatchPreHook(event, hc)
                .flatMap(preResult -> {
                    if (preResult != null) return Mono.just(preResult);
                    return executeWithApproval(param, event, hc, ctx)
                            .flatMap(result -> dispatchPostHook(param, result, hc));
                })
                .map(r -> r.withCallId(param.getCallId()));
    }

    // ---- Step 1: 构建上下文 ----

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

    // ---- Step 2: PRE Hook 分发 ----

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

    // ---- Step 3: 执行 + 审批处理 ----

    private Mono<ToolResult> executeWithApproval(ToolCallContext param, ToolCallEvent event,
                                                   HookContext hc, LoopContext ctx) {
        return aroundHookChain.aroundToolCall(event, hc,
                        (HookEvent e) -> toolExecutor.execute(param)
                                .map(result -> {
                                    e.setPayload("tool_result", result);
                                    ((ToolCallEvent) e).setResult(result);
                                    return e;
                                }))
                .flatMap(e -> Mono.justOrEmpty((ToolResult) e.getPayload("tool_result")))
                .onErrorResume(ToolSuspendException.class, e ->
                        handleSuspension(param, event, hc, ctx, e));
    }

    // ---- 审批/挂起处理 ----

    private Mono<ToolResult> handleSuspension(ToolCallContext param, ToolCallEvent event,
                                                HookContext hc, LoopContext ctx,
                                                ToolSuspendException e) {
        ApprovalStore approvalStore = toolExecutor.getApprovalStore();
        if (!param.isApproved() && approvalStore != null && event.getTool() != null) {
            String sessionId = ctx.getSessionId();
            if (sessionId != null && approvalStore.isApproved(sessionId, e.getBypassKey())) {
                param.setApproved(true);
                return toolExecutor.execute(param);
            }
        }
        InterruptEvent ie = new InterruptEvent(e.getQuestion(), param.getToolName());
        ie.setPayload(EventPayload.ARGUMENTS, param.getArgumentsMap());
        ie.setPayload(EventPayload.RECENT_MESSAGES, ctx.getMessages());
        if (event.getTool() != null) {
            ie.setPayload(EventPayload.TOOL_DESCRIPTION, event.getTool().getDescription());
            ie.setPayload(EventPayload.RISK_LEVEL, event.getTool().getRiskLevel());
        }
        return hookDispatcher.dispatch(ie, hc)
                .flatMap(ir -> {
                    if (ir.isAbort()) {
                        return Mono.just(ToolResult.failure(UI.APPROVAL_DENIED));
                    }
                    ctx.interrupt();
                    return Mono.just(ToolResult.failure(UI.APPROVAL_WAITING + e.getQuestion()));
                });
    }

    // ---- Step 4: POST Hook 分发 ----

    private Mono<ToolResult> dispatchPostHook(ToolCallContext param, ToolResult result, HookContext hc) {
        ToolCallEvent post = new ToolCallEvent(HookEventType.POST_TOOL_CALL, param, result);
        return hookDispatcher.dispatch(post, hc).thenReturn(result);
    }

    private HookContext buildHookContext(LoopContext ctx) {
        return new HookContext(ctx.getAgentName(), ctx.getRequestId(),
                ctx.getTenantId(), ctx.getSessionId(),
                ctx.getUserId(), ctx.getIteration(),
                List.of(), ctx.getAttributes());
    }
}
