package cd.lan1akea.core.tool.mcp;

import cd.lan1akea.core.tool.Tool;
import cd.lan1akea.core.tool.ToolCallContext;
import cd.lan1akea.core.tool.ToolResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP 集成测试。用 JDK 内置 HttpServer 启动嵌入式 MCP 服务器，
 * 测试 McpClient 发现工具、调用工具、McpToolAdapter 全链路。
 */
class McpIntegrationTest {

    private static int port;
    private static MCPTestServer server;

    @BeforeAll
    static void startServer() throws Exception {
        port = findFreePort();
        server = new MCPTestServer(port);
        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop();
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private String endpoint() { return "http://localhost:" + port; }

    // ========================================================================
    // McpClient：连接 → 发现 → 调用
    // ========================================================================

    @Test
    void connectAndListTools() {
        McpClient client = new McpClient(new HttpSseTransport(endpoint()));
        client.connect().block();

        List<McpToolInfo> tools = client.listTools().block();
        assertNotNull(tools);
        assertTrue(tools.size() >= 2, "至少应有 echo 和 greeting 两个工具");

        McpToolInfo echo = findTool(tools, "echo");
        assertNotNull(echo, "应包含 echo 工具");
        assertEquals("回显输入的文本", echo.getDescription());

        McpToolInfo greeting = findTool(tools, "greeting");
        assertNotNull(greeting, "应包含 greeting 工具");
        assertTrue(greeting.getDescription().contains("问候"), "greeting 描述应含'问候'");

        client.close();
    }

    @Test
    void callEchoTool() {
        McpClient client = new McpClient(new HttpSseTransport(endpoint()));
        client.connect().block();

        String result = client.callTool("echo", Map.of("text", "hello-world")).block();
        assertNotNull(result);
        assertEquals("ECHO: hello-world", result);

        client.close();
    }

    @Test
    void callGreetingTool() {
        McpClient client = new McpClient(new HttpSseTransport(endpoint()));
        client.connect().block();

        String result = client.callTool("greeting", Map.of("name", "Alice")).block();
        assertNotNull(result);
        assertEquals("你好，Alice！欢迎使用 MCP 工具。", result);

        client.close();
    }

    // ========================================================================
    // McpToolAdapter：注册到 ToolRegistry → 像普通工具一样调用
    // ========================================================================

    @Test
    void adapterRegistersAndCallsMCPTools() {
        McpClient client = new McpClient(new HttpSseTransport(endpoint()));
        client.connect().block();

        List<McpToolInfo> infos = client.listTools().block();
        assertNotNull(infos);

        for (McpToolInfo info : infos) {
            McpToolAdapter adapter = new McpToolAdapter(client, info);
            ToolCallContext ctx = buildCtx(adapter.getName());

            // echo 工具
            if ("echo".equals(adapter.getName())) {
                ToolResult r = adapter.execute(buildCtx("echo", Map.of("text", "test"))).block();
                assertTrue(r.isSuccess());
                assertEquals("ECHO: test", r.getContent());
            }
            // greeting 工具
            if ("greeting".equals(adapter.getName())) {
                ToolResult r = adapter.execute(buildCtx("greeting", Map.of("name", "Bob"))).block();
                assertTrue(r.isSuccess());
                assertTrue(r.getContent().contains("Bob"));
            }
        }
        client.close();
    }

    @Test
    void adapterSchemaMatchesToolInfo() {
        McpClient client = new McpClient(new HttpSseTransport(endpoint()));
        client.connect().block();

        List<McpToolInfo> infos = client.listTools().block();
        McpToolInfo echo = findTool(infos, "echo");
        McpToolAdapter adapter = new McpToolAdapter(client, echo);

        assertEquals("echo", adapter.getName());
        assertEquals(echo.getDescription(), adapter.getDescription());

        var schema = adapter.getParameters();
        assertNotNull(schema);
        assertTrue(schema.getParametersSchema().toString().contains("text"),
            "Schema 应包含 text 参数");

        client.close();
    }

    // ========================================================================
    // 异常处理
    // ========================================================================

    @Test
    void callNonexistentToolReturnsError() {
        McpClient client = new McpClient(new HttpSseTransport(endpoint()));
        client.connect().block();

        // MCP server 返回 error
        assertThrows(McpException.class, () ->
            client.callTool("nonexistent", Map.of()).block()
        );
        client.close();
    }

    @Test
    void adapterHandlesToolError() {
        McpClient client = new McpClient(new HttpSseTransport(endpoint()));
        client.connect().block();

        McpToolInfo echo = findTool(client.listTools().block(), "echo");
        McpToolAdapter adapter = new McpToolAdapter(client, echo);

        // echo 工具缺少必填参数 text 时 MCP server 返回错误
        ToolResult result = adapter.execute(buildCtx("echo", Map.of())).block();
        assertFalse(result.isSuccess(), "缺少必填参数应返回失败");
        assertTrue(result.getErrorMessage().contains("缺少"), "错误信息应含'缺少'");

        client.close();
    }

    // ========================================================================
    // helpers
    // ========================================================================

    private static McpToolInfo findTool(List<McpToolInfo> tools, String name) {
        return tools.stream().filter(t -> name.equals(t.getName())).findFirst().orElse(null);
    }

    private static ToolCallContext buildCtx(String toolName) {
        return buildCtx(toolName, Map.of());
    }

    private static ToolCallContext buildCtx(String toolName, Map<String, Object> args) {
        return ToolCallContext.builder()
            .callId("test-" + toolName)
            .toolName(toolName)
            .arguments(args)
            .tenantId("t1").userId("u1").sessionId("s1")
            .build();
    }

    // ========================================================================
    // 嵌入式 MCP JSON-RPC 测试服务器
    // ========================================================================

    private static class MCPTestServer {
        private final int port;
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private com.sun.net.httpserver.HttpServer httpServer;

        MCPTestServer(int port) { this.port = port; }

        void start() throws Exception {
            httpServer = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(port), 0);
            httpServer.setExecutor(executor);
            httpServer.createContext("/", this::handle);
            httpServer.start();
        }

        void stop() {
            httpServer.stop(0);
            executor.shutdown();
        }

        private void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            exchange.getResponseHeaders().add("Content-Type", "application/json");

            if ("POST".equals(method)) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String response = processRequest(body);
                byte[] respBytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, respBytes.length);
                exchange.getResponseBody().write(respBytes);
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
            exchange.close();
        }

        @SuppressWarnings("unchecked")
        private String processRequest(String body) {
            JSONObject req = JSON.parseObject(body);
            String reqMethod = req.getString("method");
            int id = req.getIntValue("id");

            return switch (reqMethod) {
                case "initialize" -> jsonRpcResult(id, Map.of(
                    "protocolVersion", "2024-11-05",
                    "capabilities", Map.of("tools", Map.of()),
                    "serverInfo", Map.of("name", "test-mcp-server", "version", "1.0")
                ));
                case "tools/list" -> jsonRpcResult(id, Map.of("tools", List.of(
                    Map.of("name", "echo",
                        "description", "回显输入的文本",
                        "inputSchema", Map.of(
                            "type", "object",
                            "properties", Map.of("text", Map.of("type", "string", "description", "要回显的文本")),
                            "required", List.of("text"))),
                    Map.of("name", "greeting",
                        "description", "返回对指定名字的问候语",
                        "inputSchema", Map.of(
                            "type", "object",
                            "properties", Map.of("name", Map.of("type", "string", "description", "要问候的名字")),
                            "required", List.of("name")))
                )));
                case "tools/call" -> {
                    JSONObject params = req.getJSONObject("params");
                    String toolName = params.getString("name");
                    Map<String, Object> args = params.getObject("arguments", Map.class);
                    yield switch (toolName) {
                        case "echo" -> {
                            String text = (String) args.get("text");
                            if (text == null || text.isBlank()) {
                                yield jsonRpcError(id, -32602, "缺少必填参数: text");
                            }
                            yield jsonRpcResult(id, Map.of("content", List.of(
                                Map.of("type", "text", "text", "ECHO: " + text))));
                        }
                        case "greeting" -> {
                            String name = (String) args.getOrDefault("name", "世界");
                            yield jsonRpcResult(id, Map.of("content", List.of(
                                Map.of("type", "text", "text", "你好，" + name + "！欢迎使用 MCP 工具。"))));
                        }
                        default -> jsonRpcError(id, -32601, "未知工具: " + toolName);
                    };
                }
                default -> jsonRpcError(id, -32601, "未知方法: " + reqMethod);
            };
        }

        private String jsonRpcResult(int id, Object result) {
            return JSON.toJSONString(Map.of("jsonrpc", "2.0", "id", id, "result", result));
        }

        private String jsonRpcError(int id, int code, String message) {
            return JSON.toJSONString(Map.of("jsonrpc", "2.0", "id", id,
                "error", Map.of("code", code, "message", message)));
        }
    }
}
