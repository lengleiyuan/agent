package cd.lan1akea.core.tool;

import cd.lan1akea.core.context.RuntimeContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolCallContextTest {

    // ========================================================================
    // ToolArguments
    // ========================================================================

    @Test
    void toolArgumentsTypedAccessors() {
        ToolArguments args = new ToolArguments(Map.of(
            "str", "hello", "num", 42, "flag", true));
        assertEquals("hello", args.getString("str"));
        assertEquals(42, args.getNumber("num").intValue());
        assertTrue(args.getBoolean("flag"));
    }

    @Test
    void toolArgumentsFromJson() {
        ToolArguments args = ToolArguments.fromJson("{\"q\":\"hello\",\"limit\":10}");
        assertEquals("hello", args.getString("q"));
        assertEquals(10, args.getNumber("limit").intValue());
    }

    @Test
    void toolArgumentsEmptyDefaults() {
        ToolArguments args = new ToolArguments(null);
        assertTrue(args.isEmpty());
        assertEquals("{}", args.toJson());
    }

    // ========================================================================
    // CallerIdentity
    // ========================================================================

    @Test
    void callerIdentityFromRuntimeContext() {
        RuntimeContext rtCtx = RuntimeContext.builder()
            .tenantId("t1").userId("u1").sessionId("s1").agentName("a1")
            .attribute("dept", "engineering").build();

        CallerIdentity identity = CallerIdentity.from(rtCtx);
        assertEquals("t1", identity.getTenantId());
        assertEquals("u1", identity.getUserId());
        assertEquals("s1", identity.getSessionId());
        assertEquals("a1", identity.getAgentName());
        assertEquals("engineering", identity.getAttribute("dept"));
    }

    @Test
    void callerIdentityFromNull() {
        CallerIdentity identity = CallerIdentity.from(null);
        assertNull(identity.getTenantId());
        assertNull(identity.getUserId());
    }

    @Test
    void callerIdentityBuilder() {
        CallerIdentity identity = CallerIdentity.builder()
            .tenantId("t2").userId("u2")
            .build();
        assertEquals("t2", identity.getTenantId());
        assertEquals("u2", identity.getUserId());
        assertNull(identity.getSessionId());
    }

    // ========================================================================
    // ToolCallContext
    // ========================================================================

    @Test
    void builderWithSeparateEntities() {
        ToolArguments args = new ToolArguments(Map.of("key", "val"));
        CallerIdentity identity = CallerIdentity.builder()
            .tenantId("t").userId("u").build();

        ToolCallContext ctx = ToolCallContext.builder()
            .callId("c1").toolName("search")
            .arguments(args)
            .identity(identity)
            .build();

        assertEquals("c1", ctx.getCallId());
        assertEquals("search", ctx.getToolName());
        assertEquals("val", ctx.getArguments().getString("key"));
        assertEquals("t", ctx.getIdentity().getTenantId());
        assertEquals("u", ctx.getUserId());
    }

    @Test
    void builderFromRuntimeContext() {
        RuntimeContext rtCtx = RuntimeContext.builder()
            .tenantId("t3").userId("u3").sessionId("s3")
            .attribute("region", "us-east-1").build();

        ToolCallContext ctx = ToolCallContext.builder()
            .callId("c2").toolName("calc")
            .arguments(Map.of("expr", "2+2"))
            .from(rtCtx)
            .build();

        assertEquals("t3", ctx.getTenantId());
        assertEquals("u3", ctx.getUserId());
        assertEquals("s3", ctx.getSessionId());
        assertEquals("us-east-1", ctx.getAttribute("region"));
        assertEquals("2+2", ctx.getArguments().getString("expr"));
    }

    @Test
    void convenienceDelegates() {
        ToolCallContext ctx = ToolCallContext.builder()
            .callId("c3").toolName("t")
            .arguments(Map.of("name", "test"))
            .tenantId("tx").userId("ux").sessionId("sx")
            .build();

        assertEquals("tx", ctx.getTenantId());
        assertEquals("ux", ctx.getUserId());
        assertEquals("sx", ctx.getSessionId());
        assertEquals("test", ctx.getString("name"));
    }

    @Test
    void ofFactoryMethod() {
        ToolCallContext ctx = ToolCallContext.of("c4", "echo", Map.of("msg", "hi"));
        assertEquals("c4", ctx.getCallId());
        assertEquals("echo", ctx.getToolName());
        assertEquals("hi", ctx.getArguments().getString("msg"));
        assertNull(ctx.getTenantId()); // no identity
    }

    @Test
    void argumentsJsonRoundtrip() {
        ToolCallContext ctx = ToolCallContext.builder()
            .callId("c5").toolName("json_test")
            .argumentsJson("{\"x\":1,\"y\":2}")
            .build();

        assertEquals(1, ctx.getArguments().getNumber("x").intValue());
        assertEquals(2, ctx.getArguments().getNumber("y").intValue());
    }

    @Test
    void attributesAccessible() {
        ToolCallContext ctx = ToolCallContext.builder()
            .callId("c6").toolName("attr_test")
            .arguments(Map.of())
            .attributes(Map.of("biz_key", "biz_val"))
            .build();

        assertEquals("biz_val", ctx.getAttribute("biz_key"));
        assertEquals("biz_val", ctx.getAttributes().get("biz_key"));
    }

    @Test
    void typedArgumentGettersViaConvenience() {
        ToolCallContext ctx = ToolCallContext.builder()
            .callId("c7").toolName("typed")
            .arguments(Map.of("intVal", 100, "boolVal", false, "strVal", "text"))
            .build();

        assertEquals(100, ctx.getNumber("intVal").intValue());
        assertFalse(ctx.getBoolean("boolVal"));
        assertEquals("text", ctx.getString("strVal"));
    }
}
