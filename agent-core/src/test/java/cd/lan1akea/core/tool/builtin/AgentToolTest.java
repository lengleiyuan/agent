package cd.lan1akea.core.tool.builtin;

import cd.lan1akea.core.CoreConstants.JsonSchema;
import cd.lan1akea.core.CoreConstants.RuntimeCtx;
import cd.lan1akea.core.agent.ReActAgent;
import cd.lan1akea.core.agent.config.AgentConfig;
import cd.lan1akea.core.metrics.AgentMetrics;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.tool.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentToolTest {

    @Mock private ChatModel model;

    private ToolRegistry toolRegistry;
    private AgentTool agentTool;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(model.getDefaultMaxTokens()).thenReturn(2048);
        when(model.getDefaultTemperature()).thenReturn(0.7);
        when(model.getMaxInputTokens()).thenReturn(8192);
        when(model.getModelName()).thenReturn("test-model");
        when(model.getProvider()).thenReturn("test");
        when(model.streamWithTools(any(), any(), any()))
                .thenReturn(Flux.just(ChatStreamChunk.builder().delta("ok").finishReason("stop").build()));

        toolRegistry = new ToolRegistry();
        agentTool = new AgentTool("sub", "子Agent", model, toolRegistry, 3, 2);
    }

    @Test
    void getName_shouldReturnName() {
        assertEquals("sub", agentTool.getName());
    }

    @Test
    void getDescription_shouldReturnDescription() {
        assertEquals("子Agent", agentTool.getDescription());
    }

    @Test
    void getTimeoutMs_shouldReturn120s() {
        assertEquals(120_000, agentTool.getTimeoutMs());
    }

    @Test
    void getParameters_shouldUseJsonSchemaConstants() {
        ToolSchema schema = agentTool.getParameters();
        Map<String, Object> props = (Map<String, Object>) schema.getParametersSchema().get(JsonSchema.PROPERTIES);
        assertNotNull(props);
        assertTrue(props.containsKey("task"));
    }

    @Test
    void execute_missingTask_shouldReturnFailure() {
        ToolCallContext ctx = ToolCallContext.of("c1", "sub", Map.of());
        ToolResult result = agentTool.execute(ctx).block();
        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("task"));
    }

    @Test
    void execute_emptyTask_shouldReturnFailure() {
        ToolCallContext ctx = ToolCallContext.of("c1", "sub", Map.of("task", ""));
        ToolResult result = agentTool.execute(ctx).block();
        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("task"));
    }

    @Test
    void defaultConstructor_shouldSetDefaults() {
        AgentTool tool = new AgentTool("x", "desc", model, toolRegistry);
        assertEquals("x", tool.getName());
        assertEquals("desc", tool.getDescription());
    }

    @Test
    void fullConstructor_shouldSetAllParams() {
        AgentTool tool = new AgentTool("a", "b", model, toolRegistry, 10, 5);
        assertEquals("a", tool.getName());
        assertEquals("b", tool.getDescription());
    }
}
