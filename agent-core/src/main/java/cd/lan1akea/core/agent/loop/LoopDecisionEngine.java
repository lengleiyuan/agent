package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.CoreConstants.Prompt;
import cd.lan1akea.core.CoreConstants.UI;
import cd.lan1akea.core.CoreConstants.FinishReason;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.MsgRole;
import cd.lan1akea.core.message.SystemMessage;
import cd.lan1akea.core.model.ChatResponse;
import cd.lan1akea.core.model.ChatUsage;

/**
 * ReAct 循环状态机决策引擎。
 * 纯同步逻辑，不依赖 Reactor、ChatModel、ToolExecutor。
 * 可直接单元测试。
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
        if (current.isAct()) {
            return Decision.continue_(Phase.observe());
        }
        if (current.isObserve()) {
            return evaluateObserve(ctx);
        }
        /* Reason 阶段：由调用方（LoopExecutor）在模型返回后检查工具调用并决定下一步 */
        return Decision.continue_(current);
    }

    /**
     * Guard 阶段：检查最大迭代次数。
     * 中断检查在 LoopExecutor 中异步处理（需 Hook 分发）。
     */
    private Decision evaluateGuard(LoopContext ctx) {
        if (ctx.getIteration() >= ctx.getMaxIterations()) {
            ctx.addMessage(SystemMessage.of(
                    Prompt.MAX_ITERATIONS_SUMMARY + Prompt.MAX_ITERATIONS_NO_TOOLS));
        }
        return Decision.continue_(Phase.reason());
    }

    /**
     * Observe 阶段：递增迭代次数，回到 Guard。
     */
    private Decision evaluateObserve(LoopContext ctx) {
        ctx.setIteration(ctx.getIteration() + 1);
        return Decision.continue_(Phase.guard());
    }

    /**
     * 构建中断终止响应。
     */
    public static ChatResponse buildInterruptedResponse(String reason) {
        Msg msg = Msg.builder(MsgRole.ASSISTANT)
                .addText(UI.INTERRUPT_PREFIX + reason + UI.INTERRUPT_SUFFIX)
                .putMetadata("interruptId", reason)
                .build();
        return new ChatResponse(msg, new ChatUsage(0, 0), FinishReason.INTERRUPTED, "");
    }
}
