package cd.lan1akea.core.agent.loop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InterventionStateTest {

    @Test
    void newState_shouldHaveNullFields() {
        LoopContext.InterventionState state = new LoopContext.InterventionState();

        assertNull(state.getInterventionId());
        assertNull(state.getInterventionType());
        assertNull(state.getPausedToolArgs());
        assertFalse(state.hasPending());
    }

    @Test
    void setAndGet_shouldWork() {
        LoopContext.InterventionState state = new LoopContext.InterventionState();

        state.setInterventionId("int_1");
        state.setInterventionType("TOOL_APPROVAL");
        state.setPausedToolArgs("{\"amount\":100}");

        assertEquals("int_1", state.getInterventionId());
        assertEquals("TOOL_APPROVAL", state.getInterventionType());
        assertEquals("{\"amount\":100}", state.getPausedToolArgs());
        assertTrue(state.hasPending());
    }

    @Test
    void clear_shouldResetAllFields() {
        LoopContext.InterventionState state = new LoopContext.InterventionState();
        state.setInterventionId("int_1");
        state.setInterventionType("TOOL_APPROVAL");
        state.setPausedToolArgs("{\"amount\":100}");

        state.clear();

        assertNull(state.getInterventionId());
        assertNull(state.getInterventionType());
        assertNull(state.getPausedToolArgs());
        assertFalse(state.hasPending());
    }

    @Test
    void hasPending_shouldReturnFalseWhenIdIsNull() {
        LoopContext.InterventionState state = new LoopContext.InterventionState();
        state.setInterventionType("TOOL_APPROVAL");

        assertFalse(state.hasPending());
    }

    @Test
    void hasPending_shouldReturnTrueWhenIdIsSet() {
        LoopContext.InterventionState state = new LoopContext.InterventionState();
        state.setInterventionId("int_1");

        assertTrue(state.hasPending());
    }
}
