package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.CoreConstants.EventPayload;
import cd.lan1akea.core.CoreConstants.Prompt;
import cd.lan1akea.core.CoreConstants.UI;
import cd.lan1akea.core.CoreConstants.FinishReason;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.MsgRole;
import cd.lan1akea.core.message.SystemMessage;
import cd.lan1akea.core.model.ChatResponse;
import cd.lan1akea.core.model.ChatUsage;
import cd.lan1akea.core.message.ToolUseBlock;

import java.util.List;

/**
 * ReAct 循环状态机决策引擎。
 * 纯同步逻辑，不依赖 Reactor、ChatModel、ToolExecutor。
 * 可直接单元测试。
 *
 * <p>四阶段评估：GUARD → REASON → ACT → OBSERVE → GUARD → ...
 * Stop 仅在 Guard 检查 complete 标记时返回，是状态机的唯一终止出口。
 */
public class LoopDecisionEngine {

    /**
     * 评估当前阶段，返回下一步决策。
     *
     * @param current 当前阶段
     * @param ctx     循环上下文
     * @return 决策（继续或终止）
     */
    public Decision evaluate(Phase current, LoopContext ctx) {
        if (current.isGuard()) {
            return evaluateGuard(ctx);
        }
        if (current.isReason()) {
            return evaluateReason(ctx);
        }
        if (current.isAct()) {
            return Decision.continue_(Phase.observe());
        }
        if (current.isObserve()) {
            return Decision.continue_(Phase.guard());
        }
        return Decision.continue_(current);
    }

    /**
     * Guard 阶段：检查完成标记和最大迭代次数。
     *
     * <p>完成标记由 REASON 阶段无工具调用时设置。
     * 达到最大迭代时注入总结提示词，但仍继续推理（禁用工具后的最后一轮）。
     */
    private Decision evaluateGuard(LoopContext ctx) {
        if (ctx.isComplete()) {
            Msg lastMsg = ctx.getLastResponse() != null
                    ? ctx.getLastResponse().getMessage() : null;
            ChatUsage usage = ctx.getLastResponse() != null
                    ? ctx.getLastResponse().getUsage() : new ChatUsage(0, 0);
            return Decision.stop(new ChatResponse(lastMsg, usage, FinishReason.STOP, ""));
        }
        if (ctx.getIteration() >= ctx.getMaxIterations()) {
            ctx.addMessage(SystemMessage.of(
                    Prompt.MAX_ITERATIONS_SUMMARY + Prompt.MAX_ITERATIONS_NO_TOOLS));
        }
        return Decision.continue_(Phase.reason());
    }

    /**
     * REASON 阶段：读 lastResponse 中的 ToolUseBlock 决定下一阶段。
     *
     * <p>有工具调用 → ACT 阶段执行工具。
     * 无工具调用 → 标记 complete → OBSERVE 阶段（做最后一次持久化后终止）。
     */
    private Decision evaluateReason(LoopContext ctx) {
        ChatResponse resp = ctx.getLastResponse();
        if (resp != null && resp.getMessage() != null) {
            List<ToolUseBlock> tools = resp.getMessage().getToolUseBlocks();
            if (tools != null && !tools.isEmpty()) {
                return Decision.continue_(Phase.act(tools));
            }
        }
        ctx.markComplete();
        return Decision.continue_(Phase.observe());
    }

    /**
     * 构建中断终止响应。
     */
    public static ChatResponse buildInterruptedResponse(String reason) {
        Msg msg = Msg.builder(MsgRole.ASSISTANT)
                .addText(UI.INTERRUPT_PREFIX + reason + UI.INTERRUPT_SUFFIX)
                .putMetadata(EventPayload.INTERRUPT_ID, reason)
                .build();
        return new ChatResponse(msg, new ChatUsage(0, 0), FinishReason.INTERRUPTED, "");
    }
}
