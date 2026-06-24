package cd.lan1akea.core.tool.mcp;

import cd.lan1akea.core.util.JsonUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpClientTest {

    private StubTransport transport;
    private McpClient client;

    @BeforeEach
    void setUp() {
        transport = new StubTransport();
        client = new McpClient(transport);
    }

    @Test
    void testInitialize() {
        transport.setInitializeResponse("""
            {"jsonrpc":"2.0","id":0,"result":{"protocolVersion":"2024-11-05"}}""");

        client.connect().block();
        assertTrue(transport.isInitialized());
    }

    @Test
    void testListTools() {
        transport.setNextResponse("""
            {"jsonrpc":"2.0","id":1,"result":{"tools":[
                {"name":"weather","description":"Get weather","inputSchema":{"type":"object","properties":{"city":{"type":"string"}}}},
                {"name":"search","description":"Search web","inputSchema":{"type":"object","properties":{"query":{"type":"string"}}}}
            ]}}""");

        List<McpToolInfo> tools = client.listTools().block();
        assertNotNull(tools);
        assertEquals(2, tools.size());

        assertEquals("weather", tools.get(0).getName());
        assertEquals("Get weather", tools.get(0).getDescription());
        assertNotNull(tools.get(0).getInputSchema());
        assertTrue(tools.get(0).getInputSchema().toString().contains("city"));

        assertEquals("search", tools.get(1).getName());
    }

    @Test
    void testListToolsEmpty() {
        transport.setNextResponse("""
            {"jsonrpc":"2.0","id":1,"result":{"tools":[]}}""");

        List<McpToolInfo> tools = client.listTools().block();
        assertNotNull(tools);
        assertTrue(tools.isEmpty());
    }

    @Test
    void testCallTool() {
        transport.setNextResponse("""
            {"jsonrpc":"2.0","id":2,"result":{"content":[
                {"type":"text","text":"Beijing: sunny, 22°C"}
            ]}}""");

        String result = client.callTool("weather", Map.of("city", "Beijing")).block();
        assertEquals("Beijing: sunny, 22°C", result);
    }

    @Test
    void testCallToolMultipleContentBlocks() {
        transport.setNextResponse("""
            {"jsonrpc":"2.0","id":2,"result":{"content":[
                {"type":"text","text":"Results: "},
                {"type":"text","text":"found 3 items"}
            ]}}""");

        String result = client.callTool("search", Map.of("query", "test")).block();
        assertEquals("Results: found 3 items", result);
    }

    @Test
    void testCallToolImageBlock() {
        transport.setNextResponse("""
            {"jsonrpc":"2.0","id":2,"result":{"content":[
                {"type":"image","data":"base64encoded","mimeType":"image/png"}
            ]}}""");

        String result = client.callTool("screenshot", Map.of()).block();
        assertTrue(result.contains("[image:"));
        assertTrue(result.contains("base64encoded"));
    }

    @Test
    void testCallToolError() {
        transport.setNextResponse("""
            {"jsonrpc":"2.0","id":2,"error":{"code":-32602,"message":"Tool not found"}}""");

        assertThrows(McpException.class, () ->
            client.callTool("nonexistent", Map.of()).block());
    }

    @Test
    void testClose() {
        client.close();
        assertFalse(transport.isConnected());
    }

    // ========================================================================
    // Stub Transport
    // ========================================================================

    static class StubTransport implements McpTransport {
        private String nextResponse;
        private boolean initialized;
        private boolean connected = true;

        void setNextResponse(String response) { this.nextResponse = response; }

        void setInitializeResponse(String response) {
            this.nextResponse = response;
        }

        boolean isInitialized() { return initialized; }

        @Override
        public Mono<Void> initialize() {
            initialized = true;
            return Mono.empty();
        }

        @Override
        public Mono<String> send(String request) {
            if (nextResponse == null) {
                return Mono.error(new RuntimeException("no response configured"));
            }
            String resp = nextResponse;
            nextResponse = null;
            return Mono.just(resp);
        }

        @Override
        public boolean isConnected() { return connected; }

        @Override
        public void close() { connected = false; }
    }
}
