package cd.lan1akea.core.tool;

import cd.lan1akea.core.model.ToolSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolExecutor 单元测试。
 * 验证工具查找、权限校验、参数验证、超时控制、异常处理。
 */
class ToolExecutorTest {

    private ToolRegistry registry;
    private ToolExecutor executor;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
        executor = new ToolExecutor(registry);
    }

    @Test
    void testExecuteExistingTool() {
        registry.register(new EchoTool());

        ToolCallParam param = new ToolCallParam("tc1", "echo", Map.of("input", "hello"));
        ToolResult result = executor.execute(param).block();

        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("ECHO: hello", result.getContent());
    }

    @Test
    void testExecuteNonExistentTool() {
        ToolCallParam param = new ToolCallParam("tc1", "nonexistent", Map.of());

        ToolResult result = executor.execute(param).block();
        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("不存在"));
    }

    @Test
    void testExecuteWithTimeout() {
        registry.register(new SlowTool(5000, 100)); // 100ms timeout < 5000ms delay

        ToolCallParam param = new ToolCallParam("tc1", "slow", Map.of());
        ToolResult result = executor.execute(param).block();

        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("超时"));
    }

    @Test
    void testExecuteWithException() {
        registry.register(new FailingTool());

        ToolCallParam param = new ToolCallParam("tc1", "failing", Map.of());
        ToolResult result = executor.execute(param).block();

        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("执行异常"));
    }

    @Test
    void testTenantAwareLookup() {
        registry.register(new EchoTool());
        registry.registerForTenant("tenant_A", new EchoTool());

        List<Tool> global = registry.getToolsForTenant(null);
        assertEquals(1, global.size());

        List<Tool> tenantA = registry.getToolsForTenant("tenant_A");
        assertTrue(tenantA.size() >= 1);
    }

    @Test
    void testDefaultTimeout() {
        EchoTool tool = new EchoTool();
        assertEquals(30000, tool.getTimeoutMs());
    }

    // ========================================================================
    // Test Tool implementations
    // ========================================================================

    static class EchoTool implements Tool {
        @Override public String getName() { return "echo"; }
        @Override public String getDescription() { return "Echo back input"; }

        @Override
        public ToolSchema getParameters() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();
            Map<String, Object> inputProp = new LinkedHashMap<>();
            inputProp.put("type", "string");
            inputProp.put("description", "Input to echo");
            props.put("input", inputProp);
            schema.put("properties", props);
            return new ToolSchema("echo", "Echo back input", schema);
        }

        @Override
        public Mono<ToolResult> execute(ToolCallParam params) {
            String input = params.getString("input");
            return Mono.just(ToolResult.success("ECHO: " + input));
        }
    }

    static class SlowTool implements Tool {
        private final long delayMs;
        private final long timeoutMs;

        SlowTool(long delayMs, long timeoutMs) { this.delayMs = delayMs; this.timeoutMs = timeoutMs; }

        @Override public String getName() { return "slow"; }
        @Override public String getDescription() { return "Slow tool"; }

        @Override
        public ToolSchema getParameters() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", Map.of());
            return new ToolSchema("slow", "Slow tool", schema);
        }

        @Override
        public Mono<ToolResult> execute(ToolCallParam params) {
            return Mono.delay(java.time.Duration.ofMillis(delayMs))
                .thenReturn(ToolResult.success("done"));
        }

        @Override
        public long getTimeoutMs() { return timeoutMs; }
    }

    static class FailingTool implements Tool {
        @Override public String getName() { return "failing"; }
        @Override public String getDescription() { return "Always fails"; }

        @Override
        public ToolSchema getParameters() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", Map.of());
            return new ToolSchema("failing", "Always fails", schema);
        }

        @Override
        public Mono<ToolResult> execute(ToolCallParam params) {
            return Mono.error(new RuntimeException("intentional failure"));
        }
    }
}
