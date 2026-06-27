package cd.lan1akea.harness.tool;

import cd.lan1akea.core.model.ToolSchema;
import cd.lan1akea.core.tool.ToolResult;
import cd.lan1akea.harness.context.ToolContext;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IToolTest {

    // ========================================================================
    // 实现 harness Tool 接口
    // ========================================================================

    static class SearchTool implements ITool {
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
        public Mono<ToolResult> execute(ToolContext ctx) {
            String q = ctx.getString("q");
            return Mono.just(ToolResult.success("result: " + q));
        }
    }

    @Test
    void harnessToolImplementsCoreTool() {
        ITool tool = new SearchTool();
        assertTrue(tool instanceof cd.lan1akea.core.tool.Tool,
            "harness Tool 应是 core Tool 子类型");
    }

    @Test
    void executeWithHarnessToolContext() {
        ITool tool = new SearchTool();

        // 模拟框架调用：core ToolCallContext → ToolContext
        cd.lan1akea.core.tool.ToolCallContext coreCtx =
            cd.lan1akea.core.tool.ToolCallContext.builder()
                .callId("c1").toolName("search")
                .arguments(Map.of("q", "hello"))
                .tenantId("t").userId("u")
                .build();

        // core 接口调用（框架内部走此路径）
        ToolResult result = ((cd.lan1akea.core.tool.Tool) tool).execute(coreCtx).block();
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("hello"));
    }

    // ========================================================================
    // AbstractTool 同步基类
    // ========================================================================

    static class EchoTool extends IBaseTool {
        @Override public String getName() { return "echo"; }
        @Override public String getDescription() { return "回显"; }

        @Override
        public ToolSchema getParameters() {
            Map<String, Object> props = Map.of("msg", Map.of("type", "string"));
            Map<String, Object> schema = Map.of("type", "object", "properties", props);
            return new ToolSchema("echo", "回显", schema);
        }

        @Override
        protected ToolResult doExecute(ToolContext ctx) {
            return ToolResult.success("echo: " + ctx.getString("msg"));
        }
    }

    @Test
    void abstractToolSyncExecution() {
        EchoTool tool = new EchoTool();

        cd.lan1akea.core.tool.ToolCallContext coreCtx =
            cd.lan1akea.core.tool.ToolCallContext.builder()
                .callId("c2").toolName("echo")
                .arguments(Map.of("msg", "hi"))
                .build();

        ToolResult result = tool.execute(coreCtx).block();
        assertTrue(result.isSuccess());
        assertEquals("echo: hi", result.getContent());
    }
}
