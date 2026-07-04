package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.message.ToolUseBlock;
import cd.lan1akea.core.model.ChatResponse;
import cd.lan1akea.core.model.ChatUsage;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

class PhaseTest {

    @Test
    void guard_shouldHaveCorrectType() {
        Phase p = Phase.guard();
        assertEquals(Phase.Type.GUARD, p.getType());
        assertTrue(p.isGuard());
        assertFalse(p.isReason());
        assertFalse(p.isAct());
        assertFalse(p.isObserve());
    }

    @Test
    void reason_shouldHaveCorrectType() {
        Phase p = Phase.reason();
        assertEquals(Phase.Type.REASON, p.getType());
        assertTrue(p.isReason());
    }

    @Test
    void act_shouldStoreToolCalls() {
        List<ToolUseBlock> tools = new ArrayList<>();
        Phase p = Phase.act(tools);
        assertEquals(Phase.Type.ACT, p.getType());
        assertTrue(p.isAct());
        assertNotNull(p.getToolCalls());
    }

    @Test
    void act_shouldDefensiveCopy() {
        List<ToolUseBlock> tools = new ArrayList<>();
        Phase p = Phase.act(tools);
        tools.add(new ToolUseBlock("id1", "test", "{}"));
        assertTrue(p.getToolCalls().isEmpty()); // original list mutated, copy untouched
    }

    @Test
    void observe_shouldHaveCorrectType() {
        Phase p = Phase.observe();
        assertEquals(Phase.Type.OBSERVE, p.getType());
        assertTrue(p.isObserve());
    }
}

class DecisionTest {

    @Test
    void continue_shouldNotBeStop() {
        Decision d = Decision.continue_(Phase.reason());
        assertFalse(d.isStop());
        assertNotNull(d.getNextPhase());
        assertTrue(d.getNextPhase().isReason());
    }

    @Test
    void stop_shouldBeStop() {
        ChatResponse resp = new ChatResponse(null, new ChatUsage(0, 0), "stop", "");
        Decision d = Decision.stop(resp);
        assertTrue(d.isStop());
        assertNotNull(d.getResponse());
        assertEquals("stop", d.getResponse().getFinishReason());
    }
}
