package cd.lan1akea.harness;

import cd.lan1akea.core.hook.Hook;
import cd.lan1akea.core.hook.HookContext;
import cd.lan1akea.core.hook.HookEvent;
import cd.lan1akea.core.hook.HookEventType;
import cd.lan1akea.core.hook.HookResult;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.state.AgentStateStore;
import cd.lan1akea.core.state.InMemoryAgentStateStore;
import cd.lan1akea.core.tool.Tool;
import cd.lan1akea.core.tool.ToolCallContext;
import cd.lan1akea.core.tool.ToolResult;
import cd.lan1akea.core.model.ToolSchema;
import cd.lan1akea.core.tool.builtin.CalculatorTool;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HarnessAgentBuilderTest {

    @Test
    void testBuilderMinimalConfig() {
        HarnessAgent agent = HarnessAgent.builder()
            .name("MinimalAgent")
            .model(new StubModel())
            .build();

        assertNotNull(agent);
        assertEquals("MinimalAgent", agent.getName());
    }

    @Test
    void testBuilderWithTools() {
        HarnessAgent agent = HarnessAgent.builder()
            .name("ToolAgent")
            .model(new StubModel())
            .tool(new CalculatorTool())
            .tools(new StubTool("tool_a"), new StubTool("tool_b"))
            .build();

        assertNotNull(agent);
        assertTrue(agent.getDelegate().getToolRegistry().size() >= 3);
    }

    @Test
    void testBuilderWithHooks() {
        HarnessAgent agent = HarnessAgent.builder()
            .name("HookAgent")
            .model(new StubModel())
            .hook(new StubHook("h1"))
            .hooks(new StubHook("h2"), new StubHook("h3"))
            .build();

        assertNotNull(agent);
    }

    @Test
    void testBuilderWithStateStore() {
        AgentStateStore store = new InMemoryAgentStateStore();
        HarnessAgent agent = HarnessAgent.builder()
            .name("StateAgent")
            .model(new StubModel())
            .stateStore(store)
            .build();

        assertSame(store, agent.getDelegate().getStateStore());
    }

    @Test
    void testBuilderWithExecutionConfig() {
        cd.lan1akea.core.agent.config.AgentExecutionConfig execConfig =
            cd.lan1akea.core.agent.config.AgentExecutionConfig.builder()
                .maxIterations(5)
                .temperature(0.5)
                .maxTokens(1024)
                .build();

        HarnessAgent agent = HarnessAgent.builder()
            .name("ExecAgent")
            .model(new StubModel())
            .executionConfig(execConfig)
            .build();

        assertEquals(5, agent.getDelegate().getConfig().getExecutionConfig().getMaxIterations());
        assertEquals(0.5, agent.getDelegate().getConfig().getExecutionConfig().getTemperature());
    }

    @Test
    void testBuilderFullConfig() {
        HarnessAgent agent = HarnessAgent.builder()
            .name("FullAgent")
            .model(new StubModel())
            .tool(new CalculatorTool())
            .tool(new StubTool("custom"))
            .hook(new StubHook("audit"))
            .stateStore(new InMemoryAgentStateStore())
            .executionConfig(cd.lan1akea.core.agent.config.AgentExecutionConfig.builder()
                .maxIterations(3).build())
            .build();

        assertNotNull(agent);
        assertTrue(agent.getDelegate().isBuilt());
        assertTrue(agent.getDelegate().getToolRegistry().size() >= 2);
        assertNotNull(agent.getDelegate().getStateStore());
    }

    // ========================================================================
    // Stubs
    // ========================================================================

    static class StubModel extends ChatModelBase {
        StubModel() {
            super("test", "stub", msgs -> List.of(Map.of("role", "user", "content", "test")));
        }

        @Override protected Map<String, String> buildAuthHeaders() { return Map.of(); }
        @Override protected String buildApiUrl() { return "http://localhost/stub"; }

        @Override
        protected Mono<ChatResponse> doChat(List<Map<String, Object>> messages,
                                            List<ToolSchema> toolSchemas,
                                            GenerateOptions options) {
            return Mono.just(new ChatResponse(
                cd.lan1akea.core.message.AssistantMessage.of("ok"),
                new ChatUsage(0, 0), "stop", null));
        }

        @Override
        protected reactor.core.publisher.Flux<ChatStreamChunk> doStream(
            List<Map<String, Object>> messages, List<ToolSchema> toolSchemas,
            GenerateOptions options) {
            return reactor.core.publisher.Flux.empty();
        }
    }

    static class StubTool implements Tool {
        private final String name;
        StubTool(String name) { this.name = name; }

        @Override public String getName() { return name; }
        @Override public String getDescription() { return "stub"; }

        @Override
        public ToolSchema getParameters() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", Map.of());
            return new ToolSchema(name, "stub", schema);
        }

        @Override
        public Mono<ToolResult> execute(ToolCallContext params) {
            return Mono.just(ToolResult.success("ok"));
        }
    }

    static class StubHook implements Hook {
        private final String name;
        StubHook(String name) { this.name = name; }

        @Override public String getName() { return name; }
        @Override public Set<HookEventType> getSubscribedEventTypes() { return Set.of(HookEventType.PRE_REASONING); }

        @Override
        public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
            return Mono.just(HookResult.continue_());
        }
    }
}
