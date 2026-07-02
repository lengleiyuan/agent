package cd.lan1akea.harness.tool;

import cd.lan1akea.core.model.ToolSchema;
import cd.lan1akea.core.tool.Tool;
import cd.lan1akea.core.tool.ToolBase;
import cd.lan1akea.core.tool.ToolCallContext;
import cd.lan1akea.core.tool.ToolResult;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 业务方直接使用 core 的 Tool / ToolBase 接口创建工具的测试。
 */
class IToolTest {

    // ── 实现 Tool 接口 ──

    static class SearchTool implements Tool {
        @Override public String getName() { return "search"; }
        @Override public String getDescription() { return "搜索文档"; }

        @Override
        public ToolSchema getParameters() {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("q", Map.of("type", "string", "description", "关键词"));
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", props);
            schema.put("required", List.of("q"));
            return new ToolSchema("search", "搜索文档", schema);
        }

        @Override
        public Mono<ToolResult> execute(ToolCallContext ctx) {
            String q = ctx.getString("q");
            return Mono.just(ToolResult.success("result: " + q));
        }
    }

    @Test
    void toolInterfaceExecution() {
        Tool tool = new SearchTool();

        ToolCallContext ctx = ToolCallContext.builder()
            .callId("c1").toolName("search")
            .arguments(Map.of("q", "hello"))
            .tenantId("t").userId("u")
            .build();

        ToolResult result = tool.execute(ctx).block();
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("hello"));
    }

    // ── 继承 ToolBase ──

    static class EchoTool extends ToolBase {
        EchoTool() { declareStringParam("msg", "消息内容", true); }
        @Override public String getName() { return "echo"; }
        @Override public String getDescription() { return "回显输入的消息"; }

        @Override
        public Mono<ToolResult> execute(ToolCallContext ctx) {
            validateParams(ctx);
            return Mono.just(ToolResult.success("echo: " + ctx.getString("msg")));
        }
    }

    @Test
    void toolBaseExecution() {
        EchoTool tool = new EchoTool();

        ToolCallContext ctx = ToolCallContext.builder()
            .callId("c2").toolName("echo")
            .arguments(Map.of("msg", "hi"))
            .build();

        ToolResult result = tool.execute(ctx).block();
        assertTrue(result.isSuccess());
        assertEquals("echo: hi", result.getContent());
    }

    @Test
    void toolBaseAutoGeneratesSchema() {
        EchoTool tool = new EchoTool();
        ToolSchema schema = tool.getParameters();
        assertNotNull(schema);
        String schemaStr = schema.getParametersSchema().toString();
        assertTrue(schemaStr.contains("msg"), "Schema 应包含 msg 参数");
        assertTrue(schemaStr.contains("required"), "Schema 应包含 required 字段");
    }

    @Test
    void toolBaseValidatesRequiredParams() {
        EchoTool tool = new EchoTool();
        ToolCallContext ctx = ToolCallContext.builder()
            .callId("c3").toolName("echo")
            .arguments(Map.of()) // 缺少必填 msg
            .build();

        // ToolBase.validateParams 直接抛 IllegalArgumentException，调用方应捕获
        assertThrows(IllegalArgumentException.class, () -> tool.execute(ctx).block());
    }
}
