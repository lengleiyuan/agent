package cd.lan1akea.core.tool.mcp;

import cd.lan1akea.core.model.ToolSchema;
import cd.lan1akea.core.tool.ToolCallParam;
import cd.lan1akea.core.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpToolAdapterTest {

    private StubMcpClient client;
    private McpToolAdapter adapter;

    @BeforeEach
    void setUp() {
        client = new StubMcpClient();
        McpToolInfo info = new McpToolInfo("weather", "Get weather",
            Map.of("type", "object",
                "properties", Map.of("city", Map.of("type", "string")),
                "required", java.util.List.of("city")));
        adapter = new McpToolAdapter(client, info);
    }

    @Test
    void testGetName() {
        assertEquals("weather", adapter.getName());
    }

    @Test
    void testGetDescription() {
        assertEquals("Get weather", adapter.getDescription());
    }

    @Test
    void testGetParameters() {
        ToolSchema schema = adapter.getParameters();
        assertEquals("weather", schema.getName());
        assertEquals("Get weather", schema.getDescription());
        assertNotNull(schema.getParametersSchema());
        assertTrue(schema.getParametersSchema().toString().contains("city"));
    }

    @Test
    void testGetParametersNullSchema() {
        McpToolInfo info = new McpToolInfo("bare", "no schema", null);
        McpToolAdapter bare = new McpToolAdapter(client, info);

        ToolSchema schema = bare.getParameters();
        assertNotNull(schema);
        assertEquals("bare", schema.getName());
    }

    @Test
    void testExecuteSuccess() {
        client.setResult("Beijing: sunny, 22°C");

        ToolCallParam param = new ToolCallParam("c1", "weather",
            Map.of("city", "Beijing"));
        ToolResult result = adapter.execute(param).block();

        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("Beijing: sunny, 22°C", result.getContent());
    }

    @Test
    void testExecuteFailure() {
        client.setError(new McpException("Tool not found"));

        ToolCallParam param = new ToolCallParam("c1", "weather", Map.of());
        ToolResult result = adapter.execute(param).block();

        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("MCP工具"));
        assertTrue(result.getErrorMessage().contains("Tool not found"));
    }

    @Test
    void testGetTimeoutMs() {
        assertEquals(60_000, adapter.getTimeoutMs());
    }

    // ========================================================================
    // Stub Client
    // ========================================================================

    static class StubMcpClient extends McpClient {
        private String result;
        private RuntimeException error;

        StubMcpClient() {
            super(new StubTransport());
        }

        void setResult(String r) { this.result = r; }
        void setError(RuntimeException e) { this.error = e; }

        @Override
        public Mono<String> callTool(String toolName, Map<String, Object> arguments) {
            if (error != null) return Mono.error(error);
            return Mono.justOrEmpty(result);
        }
    }

    static class StubTransport implements McpTransport {
        @Override public Mono<Void> initialize() { return Mono.empty(); }
        @Override public Mono<String> send(String r) { return Mono.empty(); }
        @Override public boolean isConnected() { return true; }
        @Override public void close() {}
    }
}
