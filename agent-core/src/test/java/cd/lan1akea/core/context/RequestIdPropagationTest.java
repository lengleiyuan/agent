package cd.lan1akea.core.context;

import cd.lan1akea.core.agent.loop.LoopContext;
import cd.lan1akea.core.hook.HookContext;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.UserMessage;
import cd.lan1akea.core.model.GenerateOptions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RequestId 全链路传播测试。
 * 验证 RuntimeContext → LoopContext → HookContext 的 requestId 传播。
 */
class RequestIdPropagationTest {

    @Test
    void runtimeContextAutoGeneratesRequestId() {
        RuntimeContext ctx = RuntimeContext.builder().tenantId("t1").build();
        assertNotNull(ctx.getRequestId());
        assertFalse(ctx.getRequestId().isBlank());
    }

    @Test
    void runtimeContextAcceptsCustomRequestId() {
        RuntimeContext ctx = RuntimeContext.builder().requestId("custom-123").tenantId("t1").build();
        assertEquals("custom-123", ctx.getRequestId());
    }

    @Test
    void runtimeContextNullRequestIdFallsBackToUuid() {
        RuntimeContext ctx = new RuntimeContext(null, "t1", "u1", "s1", "agent", null);
        assertNotNull(ctx.getRequestId());
        assertEquals(36, ctx.getRequestId().length()); // UUID length
    }

    @Test
    void runtimeContextEmptyDefaultsToNullTenant() {
        RuntimeContext ctx = RuntimeContext.empty();
        assertNotNull(ctx.getRequestId());
        assertNull(ctx.getTenantId());
    }

    @Test
    void loopContextCopiesRequestIdFromRuntimeContext() {
        RuntimeContext ctx = RuntimeContext.builder().requestId("rid-456").tenantId("t1").build();
        LoopContext loop = LoopContext.builder()
            .agentName("test").fromRuntimeContext(ctx)
            .messages(List.of()).generateOptions(GenerateOptions.defaults())
            .maxIterations(5).stream(false).build();
        assertEquals("rid-456", loop.getRequestId());
    }

    @Test
    void loopContextAutoGeneratesRequestIdWithoutRuntimeContext() {
        LoopContext loop = LoopContext.builder()
            .agentName("test").messages(List.of()).generateOptions(GenerateOptions.defaults())
            .maxIterations(5).stream(false).build();
        assertNotNull(loop.getRequestId());
        assertFalse(loop.getRequestId().isBlank());
    }

    @Test
    void loopContextAcceptsCustomRequestId() {
        LoopContext loop = LoopContext.builder()
            .agentName("test").requestId("direct-rid")
            .messages(List.of()).generateOptions(GenerateOptions.defaults())
            .maxIterations(5).stream(false).build();
        assertEquals("direct-rid", loop.getRequestId());
    }

    @Test
    void hookContextReceivesRequestIdFromConstructor() {
        HookContext hc = new HookContext("agent", "rid-hook", "t1", "s1", "u1", 3, List.of(), null);
        assertEquals("rid-hook", hc.getRequestId());
    }

    @Test
    void hookContextFromRuntimeContextPropagatesRequestId() {
        RuntimeContext rt = RuntimeContext.builder().requestId("from-rt").tenantId("t1").agentName("a").build();
        HookContext hc = HookContext.from(rt, 0);
        assertEquals("from-rt", hc.getRequestId());
    }

    @Test
    void hookContextWithToolsPropagatesRequestId() {
        RuntimeContext rt = RuntimeContext.builder().requestId("rt-tools").tenantId("t1").agentName("a").build();
        HookContext hc = HookContext.from(rt, 2, List.of("tool1"));
        assertEquals("rt-tools", hc.getRequestId());
    }

    @Test
    void hookContextLegacyConstructorDoesNotHaveRequestId() {
        // Old constructor (without requestId) still works but requestId is null
        HookContext hc = new HookContext("agent", "t1", "s1", "u1", 0, List.of(), null);
        assertNull(hc.getRequestId());
    }

    @Test
    void runtimeContextBuilderWithoutRequestIdGeneratesOne() {
        RuntimeContext ctx = RuntimeContext.builder().tenantId("t1").userId("u1").sessionId("s1").build();
        assertNotNull(ctx.getRequestId());
        String id1 = ctx.getRequestId();

        RuntimeContext ctx2 = RuntimeContext.builder().tenantId("t2").build();
        assertNotNull(ctx2.getRequestId());
        assertNotEquals(id1, ctx2.getRequestId(), "Each context should get a unique requestId");
    }

    @Test
    void nullRuntimeContextInLoopContextDoesNotPropagateRequestId() {
        LoopContext loop = LoopContext.builder()
            .agentName("test").fromRuntimeContext(null)
            .messages(List.of()).generateOptions(GenerateOptions.defaults())
            .maxIterations(5).stream(false).build();
        assertNotNull(loop.getRequestId(), "Should auto-generate when RuntimeContext is null");
    }
}
