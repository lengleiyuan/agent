package cd.lan1akea.core.intervention;

import cd.lan1akea.core.exception.HumanInterventionException;
import cd.lan1akea.core.tool.ToolCallContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

class HumanInterventionExceptionTest {

    @Test
    void approval_shouldHaveCorrectType() {
        ToolCallContext ctx = ToolCallContext.of("c1", "transfer", Map.of("amount", 100));
        HumanInterventionException e = HumanInterventionException.approval("transfer", "确认转账?", ctx);
        assertEquals(HumanInterventionException.Type.TOOL_APPROVAL, e.getType());
        assertEquals("transfer", e.getToolName());
        assertEquals("确认转账?", e.getReason());
        assertTrue(e.isResumable());
        assertNotNull(e.getCallParam());
    }

    @Test
    void clarify_shouldHaveCorrectType() {
        ToolCallContext ctx = ToolCallContext.of("c1", "transfer", Map.of("amount", 100));
        HumanInterventionException e = HumanInterventionException.clarify("transfer", "收款人?", ctx);
        assertEquals(HumanInterventionException.Type.TOOL_CLARIFY, e.getType());
        assertTrue(e.isResumable());
    }

}
