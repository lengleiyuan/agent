package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.CoreConstants.Prompt;
import cd.lan1akea.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

class LoopDecisionEngineTest {

    private final LoopDecisionEngine engine = new LoopDecisionEngine();

    private static LoopContext ctx() {
        return LoopContext.builder().agentName("test").messages(List.of()).build();
    }

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
        ctx.setIteration(3); // reached max
        Decision d = engine.evaluate(Phase.guard(), ctx);
        assertFalse(d.isStop());
        assertTrue(d.getNextPhase().isReason());
        // should have added summary system message
        String lastMsg = ctx.getMessages().get(ctx.getMessages().size() - 1).getTextContent();
        assertTrue(lastMsg.contains(Prompt.MAX_ITERATIONS_SUMMARY));
    }

    @Test
    void act_shouldContinueToObserve() {
        LoopContext ctx = ctx();
        List<ToolUseBlock> tools = List.of();
        Decision d = engine.evaluate(Phase.act(tools), ctx);
        assertFalse(d.isStop());
        assertTrue(d.getNextPhase().isObserve());
    }

    @Test
    void observe_shouldIncrementAndContinueToGuard() {
        LoopContext ctx = ctx();
        int before = ctx.getIteration();
        Decision d = engine.evaluate(Phase.observe(), ctx);
        assertFalse(d.isStop());
        assertTrue(d.getNextPhase().isGuard());
        assertEquals(before + 1, ctx.getIteration());
    }

    @Test
    void reason_shouldContinueToReason() {
        LoopContext ctx = ctx();
        Decision d = engine.evaluate(Phase.reason(), ctx);
        assertFalse(d.isStop());
        assertTrue(d.getNextPhase().isReason());
    }

    @Test
    void buildInterruptedResponse_shouldReturnChatResponse() {
        cd.lan1akea.core.model.ChatResponse resp = LoopDecisionEngine.buildInterruptedResponse("test-reason");
        assertNotNull(resp);
        assertEquals("interrupted", resp.getFinishReason());
        assertNotNull(resp.getMessage());
        assertTrue(resp.getMessage().getTextContent().contains("test-reason"));
    }
}
