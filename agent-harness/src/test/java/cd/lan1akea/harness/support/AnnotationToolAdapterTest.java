package cd.lan1akea.harness.support;

import cd.lan1akea.core.tool.Tool;
import cd.lan1akea.core.tool.ToolCallContext;
import cd.lan1akea.core.tool.ToolRegistry;
import cd.lan1akea.core.tool.ToolResult;
import cd.lan1akea.harness.annotation.ToolFunction;
import cd.lan1akea.harness.annotation.ToolParam;
import cd.lan1akea.harness.context.ToolContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnnotationToolAdapterTest {

    // ========================================================================
    // 单方法（类级 @ToolFunction）
    // ========================================================================

    @ToolFunction(description = "天气工具")
    static class SingleMethodTool {
        public ToolResult getWeather(@ToolParam(name = "city") String city) {
            return ToolResult.success("天气: " + city);
        }
    }

    @Test
    void resolveClassLevelAnnotation() {
        ToolRegistry registry = new ToolRegistry();
        registry.addAdapter(new AnnotationToolAdapter());
        List<Tool> tools = registry.registerTool(new SingleMethodTool());

        assertEquals(1, tools.size());
        Tool tool = tools.get(0);
        assertEquals("get_weather", tool.getName());
        assertTrue(tool.getDescription().contains("天气"));
    }

    // ========================================================================
    // 多方法（类级 @ToolFunction）
    // ========================================================================

    @ToolFunction
    static class MultiMethodClassLevel {
        public ToolResult create() { return ToolResult.success("created"); }
        public ToolResult delete() { return ToolResult.success("deleted"); }
        public ToolResult update() { return ToolResult.success("updated"); }
        @SuppressWarnings("unused")
        private void helper() {} // 不应注册
    }

    @Test
    void adaptionAllClassLevelPublicMethods() {
        ToolRegistry registry = new ToolRegistry();
        registry.addAdapter(new AnnotationToolAdapter());
        List<Tool> tools = registry.registerTool(new MultiMethodClassLevel());

        assertEquals(3, tools.size());
        assertTrue(registry.contains("create"));
        assertTrue(registry.contains("delete"));
        assertTrue(registry.contains("update"));
        // private 方法不应注册
        assertFalse(registry.contains("helper"));
    }

    // ========================================================================
    // 多方法（方法级 @ToolFunction）
    // ========================================================================

    static class MultiMethodAnnotated {
        @ToolFunction(name = "search_docs", description = "搜索文档")
        public ToolResult search(@ToolParam(name = "q") String q) {
            return ToolResult.success("result: " + q);
        }

        @ToolFunction(name = "index_docs", description = "索引文档")
        public ToolResult index(@ToolParam(name = "content") String content) {
            return ToolResult.success("indexed: " + content);
        }

        // 未标注 → 不注册
        public ToolResult helper() { return ToolResult.success("helper"); }
    }

    @Test
    void adaptionAllAnnotatedMethods() {
        ToolRegistry registry = new ToolRegistry();
        registry.addAdapter(new AnnotationToolAdapter());
        List<Tool> tools = registry.registerTool(new MultiMethodAnnotated());

        assertEquals(2, tools.size());
        assertTrue(registry.contains("search_docs"));
        assertTrue(registry.contains("index_docs"));
        assertFalse(registry.contains("helper"));
    }

    // ========================================================================
    // 权限码
    // ========================================================================

    @ToolFunction(name = "dangerous_op", permission = "admin:write")
    static class PermissionTool {
        public ToolResult run() { return ToolResult.success("ok"); }
    }

    @Test
    void toolCarriesPermissionCode() {
        ToolRegistry registry = new ToolRegistry();
        registry.addAdapter(new AnnotationToolAdapter());
        List<Tool> tools = registry.registerTool(new PermissionTool());

        assertEquals(1, tools.size());
        Tool tool = tools.get(0);
        assertTrue(tool.getPermissions().contains("admin:write"));
    }

    static class NoPermissionTool {
        @ToolFunction(name = "safe_op")
        public ToolResult run() { return ToolResult.success("ok"); }
    }

    @Test
    void toolWithoutPermissionHasEmptySet() {
        ToolRegistry registry = new ToolRegistry();
        registry.addAdapter(new AnnotationToolAdapter());
        List<Tool> tools = registry.registerTool(new NoPermissionTool());

        assertTrue(tools.get(0).getPermissions().isEmpty());
    }

    // ========================================================================
    // ToolCallContext 注入
    // ========================================================================

    static class ContextInjectionTool {
        @ToolFunction(name = "audit_op")
        public ToolResult audit(@ToolParam(name = "action") String action,
                                 ToolContext ctx) {
            assertNotNull(ctx, "ToolContext should be injected");
            assertEquals("tx", ctx.getTenantId());
            assertEquals("ux", ctx.getUserId());
            return ToolResult.success("audited: " + action + " by " + ctx.getUserId());
        }
    }

    @Test
    void toolContextInjectedIntoMethod() {
        ToolRegistry registry = new ToolRegistry();
        registry.addAdapter(new AnnotationToolAdapter());
        List<Tool> tools = registry.registerTool(new ContextInjectionTool());
        Tool tool = tools.get(0);

        ToolCallContext coreCtx = ToolCallContext.builder()
            .callId("x").toolName("audit_op")
            .arguments(Map.of("action", "delete"))
            .tenantId("tx").userId("ux").sessionId("sx")
            .build();

        ToolResult result = tool.execute(coreCtx).block();
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("delete"));
        assertTrue(result.getContent().contains("ux"));
        // ToolContext 不应出现在 schema 中
        assertFalse(tool.getParameters().getParametersSchema().toString().contains("ctx"));
    }

    // ========================================================================
    // 方法名 vs 注解名
    // ========================================================================

    static class NamedMethodTool {
        @ToolFunction(name = "custom_name")
        public ToolResult someMethod() { return ToolResult.success("ok"); }
    }

    @Test
    void methodLevelNameOverridesMethodName() {
        ToolRegistry registry = new ToolRegistry();
        registry.addAdapter(new AnnotationToolAdapter());
        List<Tool> tools = registry.registerTool(new NamedMethodTool());

        assertEquals("custom_name", tools.get(0).getName());
    }
}
