package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.hook.HookContext;
import cd.lan1akea.core.hook.HookEvent;
import cd.lan1akea.core.hook.HookEventType;
import cd.lan1akea.core.hook.HookPipeline;
import cd.lan1akea.core.message.ToolUseBlock;
import cd.lan1akea.core.tool.ToolCallContext;
import cd.lan1akea.core.tool.ToolExecutor;
import cd.lan1akea.core.tool.ToolResult;

import reactor.core.publisher.Mono;

/**
 * 工具调用编排器。
 *
 * <p>负责构建工具调用上下文和 Hook 事件，委托 {@link HookPipeline} 执行完整管线。
 * 介入异常不在此捕获，直接穿透到 LoopExecutor。
 */
public class ToolCallOrchestrator {

    /** 工具执行器 */
    private final ToolExecutor toolExecutor;
    /** Hook 管线门面 */
    private final HookPipeline hookPipeline;

    /**
     * 构建工具调用编排器。
     *
     * @param toolExecutor  工具执行器
     * @param hookPipeline  Hook 管线门面
     */
    public ToolCallOrchestrator(ToolExecutor toolExecutor, HookPipeline hookPipeline) {
        this.toolExecutor = toolExecutor;
        this.hookPipeline = hookPipeline;
    }

    /**
     * 执行单个工具调用，返回含 callId 的结果。
     *
     * <p>构建 PRE_TOOL_CALL 事件 → 委托 {@link HookPipeline#aroundToolCall} 执行
     * PRE_TOOL → aroundHook → POST_TOOL 全流程。
     *
     * @param tc  模型请求的工具调用块
     * @param ctx 循环上下文
     * @return 含 callId 的工具执行结果
     */
    public Mono<ToolResult> execute(ToolUseBlock tc, LoopContext ctx) {
        ToolCallContext param = buildContext(tc, ctx);
        HookContext hc = ctx.toHookContext();
        HookEvent preEvent = buildPreEvent(tc, param, ctx);

        return hookPipeline.aroundToolCall(preEvent, hc,
                        toolExecutor::execute)
                .map(r -> r.withCallId(param.getCallId()));
    }

    /**
     * 直接执行工具调用，跳过 PRE Hook（用于介入恢复场景）。
     *
     * @param param 预先构建的工具调用上下文（标记为 approved）
     * @param ctx   循环上下文
     * @return 工具执行结果的 Mono
     */
    public Mono<ToolResult> executeDirect(ToolCallContext param, LoopContext ctx) {
        HookContext hc = ctx.toHookContext();
        HookEvent event = new HookEvent(HookEventType.PRE_TOOL_CALL);
        event.setCallParam(param);

        return hookPipeline.aroundToolCallDirect(event, hc,
                    toolExecutor::execute)
                .map(r -> r.withCallId(param.getCallId()));
    }

    /**
     * 构建 PRE_TOOL_CALL Hook 事件，含 callParam 和 tool 信息。
     */
    private HookEvent buildPreEvent(ToolUseBlock tc, ToolCallContext param, LoopContext ctx) {
        HookEvent event = new HookEvent(HookEventType.PRE_TOOL_CALL);
        event.setCallParam(param);
        event.setTool(toolExecutor.getRegistry().getForContext(
                ctx.getTenantId(), ctx.getUserId(), ctx.getSessionId(), tc.getName()));
        return event;
    }

    /**
     * 从 ToolUseBlock 和 LoopContext 构建 ToolCallContext。
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
}
