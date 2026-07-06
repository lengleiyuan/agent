package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.CoreConstants.FinishReason;
import cd.lan1akea.core.CoreConstants.Prompt;
import cd.lan1akea.core.message.*;
import cd.lan1akea.core.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LoopDecisionEngineTest {

    private final LoopDecisionEngine engine = new LoopDecisionEngine();

    private static LoopContext ctx() {
        return LoopContext.builder().agentName("test").messages(List.of()).build();
    }

    // ============================================================
    // Guard 阶段
    // ============================================================

    @Test
    void guardNormal_shouldContinueToReason() {
        LoopContext ctx = ctx();
        Decision d = engine.evaluate(Phase.guard(), ctx);
        assertFalse(d.isStop());
        assertTrue(d.getNextPhase().isReason());
    }

    @Test
    void guardMaxIterations_shouldInjectSummaryAndContinueToReason() {
        LoopContext ctx = LoopContext.builder().agentName("test")
                .messages(List.of()).maxIterations(3).build();
        ctx.setIteration(3);
        Decision d = engine.evaluate(Phase.guard(), ctx);
        assertFalse(d.isStop());
        assertTrue(d.getNextPhase().isReason());
        String lastMsg = ctx.getMessages().get(ctx.getMessages().size() - 1).getTextContent();
        assertTrue(lastMsg.contains(Prompt.MAX_ITERATIONS_SUMMARY));
    }

    @Test
    void guardComplete_shouldReturnStop() {
        LoopContext ctx = ctx();
        Msg assistantMsg = AssistantMessage.of("done");
        ctx.setLastResponse(new ChatResponse(assistantMsg, new ChatUsage(10, 5), FinishReason.STOP, ""));
        ctx.markComplete();
        Decision d = engine.evaluate(Phase.guard(), ctx);
        assertTrue(d.isStop());
        assertNotNull(d.getResponse());
        assertEquals(FinishReason.STOP, d.getResponse().getFinishReason());
    }

    // ============================================================
    // REASON 阶段 — 引擎真正评估工具调用
    // ============================================================

    @Test
    void reasonWithTools_shouldContinueToAct() {
        LoopContext ctx = ctx();
        Msg msg = AssistantMessage.builder()
                .addToolUse("call_1", "search", "{}")
                .build();
        ctx.setLastResponse(new ChatResponse(msg, new ChatUsage(0, 0), "tool_calls", ""));
        Decision d = engine.evaluate(Phase.reason(), ctx);
        assertFalse(d.isStop());
        assertTrue(d.getNextPhase().isAct());
        assertNotNull(d.getNextPhase().getToolCalls());
        assertEquals(1, d.getNextPhase().getToolCalls().size());
    }

    @Test
    void reasonWithoutTools_shouldMarkCompleteAndContinueToObserve() {
        LoopContext ctx = ctx();
        Msg msg = AssistantMessage.of("all done");
        ctx.setLastResponse(new ChatResponse(msg, new ChatUsage(5, 3), FinishReason.STOP, ""));
        Decision d = engine.evaluate(Phase.reason(), ctx);
        assertFalse(d.isStop());
        assertTrue(d.getNextPhase().isObserve());
        assertTrue(ctx.isComplete());
    }

    @Test
    void reasonNullResponse_shouldMarkCompleteAndGoToObserve() {
        LoopContext ctx = ctx();
        Decision d = engine.evaluate(Phase.reason(), ctx);
        assertFalse(d.isStop());
        assertTrue(d.getNextPhase().isObserve());
        assertTrue(ctx.isComplete());
    }

    // ============================================================
    // ACT 阶段
    // ============================================================

    @Test
    void act_shouldContinueToObserve() {
        LoopContext ctx = ctx();
        Decision d = engine.evaluate(Phase.act(List.of()), ctx);
        assertFalse(d.isStop());
        assertTrue(d.getNextPhase().isObserve());
    }

    // ============================================================
    // OBSERVE 阶段
    // ============================================================

    @Test
    void observe_shouldContinueToGuard() {
        LoopContext ctx = ctx();
        Decision d = engine.evaluate(Phase.observe(), ctx);
        assertFalse(d.isStop());
        assertTrue(d.getNextPhase().isGuard());
    }

    // ============================================================
    // buildInterruptedResponse
    // ============================================================

    @Test
    void buildInterruptedResponse_shouldReturnChatResponse() {
        ChatResponse resp = LoopDecisionEngine.buildInterruptedResponse("test-reason");
        assertNotNull(resp);
        assertEquals(FinishReason.INTERRUPTED, resp.getFinishReason());
        assertNotNull(resp.getMessage());
        assertTrue(resp.getMessage().getTextContent().contains("test-reason"));
    }
}
