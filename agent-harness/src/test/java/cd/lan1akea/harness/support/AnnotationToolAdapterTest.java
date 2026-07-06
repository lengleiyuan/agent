package cd.lan1akea.harness.support;

import cd.lan1akea.core.exception.HumanInterventionException;
import cd.lan1akea.core.tool.Tool;
import cd.lan1akea.core.tool.ToolCallContext;
import cd.lan1akea.core.tool.ToolRegistry;
import cd.lan1akea.core.tool.ToolResult;
import cd.lan1akea.harness.annotation.ToolFunction;
import cd.lan1akea.harness.annotation.ToolParam;
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
                                 ToolCallContext ctx) {
            assertNotNull(ctx, "ToolCallContext should be injected");
            assertEquals("tx", ctx.getTenantId());
            assertEquals("ux", ctx.getUserId());
            return ToolResult.success("audited: " + action + " by " + ctx.getUserId());
        }
    }

    @Test
    void toolCallContextInjectedIntoMethod() {
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
        // ToolCallContext 不应出现在 schema 中
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

    // ========================================================================
    // 注解字段：timeoutMs, riskLevel, group
    // ========================================================================

    // ── 类级注解，全部指定 ──

    @ToolFunction(name = "risky_op", riskLevel = "CRITICAL", timeoutMs = 60000, group = "danger")
    static class FullConfigTool {
        public ToolResult run() { return ToolResult.success("ok"); }
    }

    @Test
    void classLevelAllFieldsPropagated() {
        ToolRegistry registry = new ToolRegistry();
        registry.addAdapter(new AnnotationToolAdapter());
        List<Tool> tools = registry.registerTool(new FullConfigTool());
        Tool tool = tools.get(0);

        assertEquals("CRITICAL", tool.getRiskLevel());
        assertEquals(60000L, tool.getTimeoutMs());
        assertEquals("danger", tool.getGroup());
    }

    // ── 类级注解，部分字段 + 默认值 ──

    @ToolFunction(name = "partial", riskLevel = "LOW")
    static class PartialConfigTool {
        public ToolResult run() { return ToolResult.success("ok"); }
    }

    @Test
    void classLevelPartialFieldsUseDefaults() {
        ToolRegistry registry = new ToolRegistry();
        registry.addAdapter(new AnnotationToolAdapter());
        List<Tool> tools = registry.registerTool(new PartialConfigTool());
        Tool tool = tools.get(0);

        assertEquals("LOW", tool.getRiskLevel(), "指定了 LOW");
        assertEquals(30000L, tool.getTimeoutMs(), "timeoutMs 用默认");
        assertEquals("default", tool.getGroup(), "group 用默认");
    }

    // ── 方法级注解，全部指定 ──

    static class MethodLevelFullConfig {
        @ToolFunction(name = "method_full", description = "全配置方法",
            riskLevel = "HIGH", timeoutMs = 120000, group = "admin")
        public ToolResult run() { return ToolResult.success("ok"); }
    }

    @Test
    void methodLevelAllFieldsPropagated() {
        ToolRegistry registry = new ToolRegistry();
        registry.addAdapter(new AnnotationToolAdapter());
        List<Tool> tools = registry.registerTool(new MethodLevelFullConfig());
        Tool tool = tools.get(0);

        assertEquals("method_full", tool.getName());
        assertEquals("HIGH", tool.getRiskLevel());
        assertEquals(120000L, tool.getTimeoutMs());
        assertEquals("admin", tool.getGroup());
    }

    // ── 方法级注解，部分字段 + 默认值 ──

    static class MethodLevelPartialConfig {
        @ToolFunction(name = "method_partial", timeoutMs = 5000)
        public ToolResult run() { return ToolResult.success("ok"); }
    }

    @Test
    void methodLevelPartialFieldsUseDefaults() {
        ToolRegistry registry = new ToolRegistry();
        registry.addAdapter(new AnnotationToolAdapter());
        List<Tool> tools = registry.registerTool(new MethodLevelPartialConfig());
        Tool tool = tools.get(0);

        assertEquals("method_partial", tool.getName());
        assertEquals(5000L, tool.getTimeoutMs(), "指定了 5000");
        assertEquals("MEDIUM", tool.getRiskLevel(), "riskLevel 用默认");
        assertEquals("default", tool.getGroup(), "group 用默认");
    }

    // ── adaptToAll 多方法，不同配置互不干扰 ──

    static class MultiConfigTool {
        @ToolFunction(name = "fast_op", timeoutMs = 1000, riskLevel = "LOW", group = "fast")
        public ToolResult fast() { return ToolResult.success("fast"); }

        @ToolFunction(name = "slow_op", timeoutMs = 60000, riskLevel = "CRITICAL", group = "slow")
        public ToolResult slow() { return ToolResult.success("slow"); }
    }

    @Test
    void adaptToAllPropagatesConfigPerMethod() {
        ToolRegistry registry = new ToolRegistry();
        registry.addAdapter(new AnnotationToolAdapter());
        List<Tool> tools = registry.registerTool(new MultiConfigTool());

        assertEquals(2, tools.size());
        Tool fast = tools.stream().filter(t -> "fast_op".equals(t.getName())).findFirst().orElseThrow();
        Tool slow = tools.stream().filter(t -> "slow_op".equals(t.getName())).findFirst().orElseThrow();

        assertEquals(1000L, fast.getTimeoutMs());
        assertEquals("LOW", fast.getRiskLevel());
        assertEquals("fast", fast.getGroup());

        assertEquals(60000L, slow.getTimeoutMs());
        assertEquals("CRITICAL", slow.getRiskLevel());
        assertEquals("slow", slow.getGroup());
    }

    // ========================================================================
    // ToolCallContext 注入 @ToolFunction
    // ========================================================================

    static class MethodLevelCtxInjection {
        @ToolFunction(name = "ctx_inject", timeoutMs = 5000, riskLevel = "HIGH")
        public ToolResult run(@ToolParam(name = "input") String input,
                              ToolCallContext ctx) {
            assertNotNull(ctx, "应当注入 ToolCallContext");
            assertNotNull(ctx.getCallId(), "callId 不应为空");
            assertNotNull(ctx.getSessionId(), "sessionId 不应为空");
            return ToolResult.success(input + "|" + ctx.getSessionId());
        }
    }

    @Test
    void toolCallContextInjectedWithMethodLevelAnnotation() {
        ToolRegistry registry = new ToolRegistry();
        registry.addAdapter(new AnnotationToolAdapter());
        List<Tool> tools = registry.registerTool(new MethodLevelCtxInjection());
        Tool tool = tools.get(0);

        // 注解字段
        assertEquals(5000L, tool.getTimeoutMs());
        assertEquals("HIGH", tool.getRiskLevel());

        // Schema 不含 ctx
        String schemaStr = tool.getParameters().getParametersSchema().toString();
        assertFalse(schemaStr.contains("ctx"), "ToolCallContext 不应出现在 schema: " + schemaStr);
        assertTrue(schemaStr.contains("input"), "业务参数应在 schema 中");

        // 执行
        ToolCallContext callCtx = ToolCallContext.builder()
            .callId("c1").toolName("ctx_inject")
            .arguments(Map.of("input", "hello"))
            .sessionId("sess-abc")
            .build();

        ToolResult result = tool.execute(callCtx).block();
        assertTrue(result.isSuccess());
        assertEquals("hello|sess-abc", result.getContent());
    }

    // ToolCallContext 放第一个参数位置

    static class CtxFirstParamTool {
        @ToolFunction(name = "ctx_first")
        public ToolResult run(ToolCallContext ctx,
                              @ToolParam(name = "msg") String msg) {
            return ToolResult.success(msg + "|" + ctx.getTenantId());
        }
    }

    @Test
    void toolCallContextAsFirstParameter() {
        ToolRegistry registry = new ToolRegistry();
        registry.addAdapter(new AnnotationToolAdapter());
        List<Tool> tools = registry.registerTool(new CtxFirstParamTool());
        Tool tool = tools.get(0);

        ToolCallContext callCtx = ToolCallContext.builder()
            .callId("c1").toolName("ctx_first")
            .arguments(Map.of("msg", "hi"))
            .tenantId("t-1")
            .build();

        ToolResult result = tool.execute(callCtx).block();
        assertTrue(result.isSuccess());
        assertEquals("hi|t-1", result.getContent());
    }

    // 多方法混合：一个有 ToolCallContext，一个没有

    static class MixedCtxTool {
        @ToolFunction(name = "with_ctx")
        public ToolResult withCtx(ToolCallContext ctx) {
            return ToolResult.success("session=" + ctx.getSessionId());
        }

        @ToolFunction(name = "no_ctx")
        public ToolResult noCtx() {
            return ToolResult.success("no context");
        }
    }

    @Test
    void mixedMethodsWithAndWithoutContext() {
        ToolRegistry registry = new ToolRegistry();
        registry.addAdapter(new AnnotationToolAdapter());
        List<Tool> tools = registry.registerTool(new MixedCtxTool());
        assertEquals(2, tools.size());

        Tool withCtx = tools.stream().filter(t -> "with_ctx".equals(t.getName())).findFirst().orElseThrow();
        Tool noCtx = tools.stream().filter(t -> "no_ctx".equals(t.getName())).findFirst().orElseThrow();

        // with_ctx：Schema 不含 ctx
        assertFalse(withCtx.getParameters().getParametersSchema().toString().contains("ctx"));

        ToolCallContext ctx = ToolCallContext.builder()
            .callId("c1").toolName("with_ctx").sessionId("s1")
            .arguments(Map.of()).build();

        assertEquals("session=s1", withCtx.execute(ctx).block().getContent());
        assertEquals("no context", noCtx.execute(ctx).block().getContent());
    }

    // ========================================================================
    // HumanInterventionException 传播
    // ========================================================================

    @ToolFunction(name = "needs_approval", riskLevel = "HIGH")
    static class ApprovalTool {
        public ToolResult run() {
            throw HumanInterventionException.approval("needs_approval", "需要审批", null);
        }
    }

    @Test
    void humanInterventionExceptionPropagatesNotSwallowed() {
        ToolRegistry registry = new ToolRegistry();
        registry.addAdapter(new AnnotationToolAdapter());
        List<Tool> tools = registry.registerTool(new ApprovalTool());
        Tool tool = tools.get(0);

        ToolCallContext ctx = ToolCallContext.builder()
            .callId("c1").toolName("needs_approval")
            .arguments(Map.of()).build();

        // HumanInterventionException 必须传播，不能被转为 ToolResult.failure
        try {
            tool.execute(ctx).block();
            fail("应该抛出 HumanInterventionException");
        } catch (HumanInterventionException e) {
            assertEquals("需要审批", e.getReason());
        }
    }

    @Test
    void normalExceptionStillConvertedToFailure() {
        @ToolFunction(name = "broken")
        class BrokenTool {
            public ToolResult run() { throw new RuntimeException("内部错误"); }
        }
        ToolRegistry registry = new ToolRegistry();
        registry.addAdapter(new AnnotationToolAdapter());
        List<Tool> tools = registry.registerTool(new BrokenTool());
        Tool tool = tools.get(0);

        ToolCallContext ctx = ToolCallContext.builder()
            .callId("c1").toolName("broken")
            .arguments(Map.of()).build();

        ToolResult result = tool.execute(ctx).block();
        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("内部错误"));
    }

    // ========================================================================
    // ToolCallContext（core 类型）注入
    // ========================================================================

    static class CoreContextInjectionTool {
        @ToolFunction(name = "core_ctx")
        public ToolResult run(@ToolParam(name = "input") String input,
                              cd.lan1akea.core.tool.ToolCallContext ctx) {
            assertNotNull(ctx);
            return ToolResult.success(input + "|" + ctx.getSessionId());
        }
    }

    @Test
    void coreToolCallContextInjectedIntoMethod() {
        ToolRegistry registry = new ToolRegistry();
        registry.addAdapter(new AnnotationToolAdapter());
        List<Tool> tools = registry.registerTool(new CoreContextInjectionTool());
        Tool tool = tools.get(0);

        ToolCallContext ctx = ToolCallContext.builder()
            .callId("c1").toolName("core_ctx")
            .arguments(Map.of("input", "hello"))
            .sessionId("sess-123")
            .build();

        ToolResult result = tool.execute(ctx).block();
        assertTrue(result.isSuccess());
        assertEquals("hello|sess-123", result.getContent());
        // core ToolCallContext 不应出现在 schema
        assertFalse(tool.getParameters().getParametersSchema().toString().contains("ctx"));
    }
}
