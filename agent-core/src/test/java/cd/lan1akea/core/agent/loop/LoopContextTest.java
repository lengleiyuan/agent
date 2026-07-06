package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.hook.HookContext;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.model.GenerateOptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LoopContextTest {

    @Test
    void toHookContext_shouldMapAllFields() {
        Map<String, Object> attrs = Map.of("key", "value");
        LoopContext ctx = LoopContext.builder()
                .agentName("test-agent")
                .requestId("req-123")
                .tenantId("tenant-1")
                .userId("user-1")
                .sessionId("session-1")
                .attributes(attrs)
                .messages(List.of())
                .generateOptions(GenerateOptions.defaults())
                .maxIterations(5)
                .stream(false)
                .build();
        ctx.setIteration(3);

        HookContext hc = ctx.toHookContext();

        assertEquals("test-agent", hc.getAgentName());
        assertEquals("req-123", hc.getRequestId());
        assertEquals("tenant-1", hc.getTenantId());
        assertEquals("session-1", hc.getSessionId());
        assertEquals("user-1", hc.getUserId());
        assertEquals(3, hc.getCurrentIteration());
        assertEquals("value", hc.getAttribute("key"));
    }

    @Test
    void toHookContext_shouldReflectCurrentIteration() {
        LoopContext ctx = LoopContext.builder()
                .agentName("a").messages(List.of())
                .generateOptions(GenerateOptions.defaults()).build();
        ctx.setIteration(7);

        assertEquals(7, ctx.toHookContext().getCurrentIteration());
    }

    @Test
    void toHookContext_calledToolsShouldBeEmpty() {
        LoopContext ctx = LoopContext.builder()
                .agentName("a").messages(List.of())
                .generateOptions(GenerateOptions.defaults()).build();

        HookContext hc = ctx.toHookContext();
        assertNotNull(hc.getCalledTools());
        assertTrue(hc.getCalledTools().isEmpty());
    }

    @Test
    void markComplete_shouldSetCompleteFlag() {
        LoopContext ctx = LoopContext.builder()
                .agentName("a").messages(List.of())
                .generateOptions(GenerateOptions.defaults()).build();
        assertFalse(ctx.isComplete());
        ctx.markComplete();
        assertTrue(ctx.isComplete());
    }

    @Test
    void newContext_shouldNotBeComplete() {
        LoopContext ctx = LoopContext.builder()
                .agentName("a").messages(List.of())
                .generateOptions(GenerateOptions.defaults()).build();
        assertFalse(ctx.isComplete());
    }
}
