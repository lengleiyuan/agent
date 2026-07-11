package cd.lan1akea.core.hook.impl;

import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.memory.Memory;
import cd.lan1akea.core.memory.MemoryEntry;
import cd.lan1akea.core.memory.MemoryRetrievalQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HookImplTest {

    // ========================================================================
    // AuditHook
    // ========================================================================

    @Test
    void testAuditHookLogsEvents() {
        AuditHook hook = new AuditHook("test-audit");

        hook.onEvent(new HookEvent(HookEventType.PRE_TOOL_CALL),
            new HookContext("agent", "t1", "s1", "u1", 0, null, null)).block();

        assertEquals(1, hook.getAuditLog().size());
        assertEquals("agent", hook.getAuditLog().get(0).getAgentName());
        assertEquals("t1", hook.getAuditLog().get(0).getTenantId());
    }

    @Test
    void testAuditHookSubscribesToMultipleEvents() {
        AuditHook hook = new AuditHook();
        var types = hook.getSubscribedEventTypes();
        assertTrue(types.contains(HookEventType.PRE_TOOL_CALL));
        assertTrue(types.contains(HookEventType.POST_TOOL_CALL));
    }

    @Test
    void testAuditHookClear() {
        AuditHook hook = new AuditHook();
        hook.onEvent(new HookEvent(HookEventType.PRE_TOOL_CALL),
            new HookContext("a", null, null, null, 0, null, null)).block();
        hook.clear();
        assertEquals(0, hook.getAuditLog().size());
    }

    @Test
    void testAuditHookGetAuditLogReturnsCopy() {
        AuditHook hook = new AuditHook();
        hook.onEvent(new HookEvent(HookEventType.PRE_TOOL_CALL),
            new HookContext("a", null, null, null, 0, null, null)).block();
        var log = hook.getAuditLog();
        log.add(null); // should not affect internal list
        assertEquals(1, hook.getAuditLog().size());
    }

    // ========================================================================
    // LoggingHook
    // ========================================================================

    @Test
    void testLoggingHookCountsEvents() {
        LoggingHook hook = new LoggingHook("test-logger");

        hook.onEvent(new HookEvent(HookEventType.PRE_REASONING),
            new HookContext("a", null, null, null, 0, null, null)).block();
        hook.onEvent(new HookEvent(HookEventType.POST_REASONING),
            new HookContext("a", null, null, null, 0, null, null)).block();

        assertEquals(2, hook.getEventCount());
    }

    @Test
    void testLoggingHookEnabled() {
        LoggingHook hook = new LoggingHook("test");
        assertTrue(hook.isEnabled());
    }

    // ========================================================================
    // ContentFilterHook
    // ========================================================================

    @Test
    void testContentFilterBlocksWord() {
        ContentFilterHook hook = new ContentFilterHook("filter",
            List.of("badword", "secret"));

        HookEvent event = new HookEvent(HookEventType.POST_REASONING);
        event.setMessages(List.of(
            cd.lan1akea.core.message.UserMessage.of("This contains badword in it")));

        HookResult result = hook.onEvent(event,
            new HookContext("a", null, null, null, 0, null, null)).block();

        assertTrue(result.isAbort());
        assertTrue(result.getAbortReason().contains("badword"));
    }

    @Test
    void testContentFilterAllowsCleanContent() {
        ContentFilterHook hook = new ContentFilterHook("filter",
            List.of("badword"));

        HookEvent event = new HookEvent(HookEventType.POST_REASONING);
        event.setMessages(List.of(
            cd.lan1akea.core.message.UserMessage.of("This is clean content")));

        HookResult result = hook.onEvent(event,
            new HookContext("a", null, null, null, 0, null, null)).block();

        assertTrue(result.isContinue());
    }

    @Test
    void testContentFilterEmptyBlockedWords() {
        ContentFilterHook hook = new ContentFilterHook();

        HookEvent event = new HookEvent(HookEventType.POST_REASONING);
        event.setMessages(List.of(
            cd.lan1akea.core.message.UserMessage.of("anything")));

        HookResult result = hook.onEvent(event,
            new HookContext("a", null, null, null, 0, null, null)).block();

        assertTrue(result.isContinue());
    }

    @Test
    void testContentFilterNonReasoningEventPasses() {
        ContentFilterHook hook = new ContentFilterHook("filter", List.of("bad"));

        HookResult result = hook.onEvent(
            new HookEvent(HookEventType.ON_INTERRUPT),
            new HookContext("a", null, null, null, 0, null, null)).block();

        assertTrue(result.isContinue());
    }

    // ========================================================================
    // RateLimitHook
    // ========================================================================

    @Test
    void testRateLimitAllowsUnderLimit() {
        RateLimitHook hook = new RateLimitHook(3, 60_000);

        for (int i = 0; i < 3; i++) {
            HookResult r = hook.onEvent(new HookEvent(HookEventType.PRE_TOOL_CALL),
                new HookContext("a", null, null, null, 0, null, null)).block();
            assertTrue(r.isContinue(), "调用 " + i + " 应在限额内");
        }
    }

    @Test
    void testRateLimitBlocksOverLimit() {
        RateLimitHook hook = new RateLimitHook(2, 60_000);

        hook.onEvent(new HookEvent(HookEventType.PRE_TOOL_CALL),
            new HookContext("a", null, null, null, 0, null, null)).block();
        hook.onEvent(new HookEvent(HookEventType.PRE_TOOL_CALL),
            new HookContext("a", null, null, null, 0, null, null)).block();

        HookResult r = hook.onEvent(new HookEvent(HookEventType.PRE_TOOL_CALL),
            new HookContext("a", null, null, null, 0, null, null)).block();

        assertTrue(r.isAbort());
        assertTrue(r.getAbortReason().contains("频率超限"));
    }

    @Test
    void testRateLimitDefaultConstructor() {
        RateLimitHook hook = new RateLimitHook();
        assertEquals("RateLimitHook", hook.getName());
        assertEquals(10, hook.getPriority());
    }

    // ========================================================================
    // MemoryEnrichmentHook
    // ========================================================================

    @Test
    void testMemoryEnrichmentHookName() {
        cd.lan1akea.core.memory.Memory memory = new cd.lan1akea.core.memory.Memory() {
            public Mono<String> store(MemoryEntry e) { return Mono.just("stub"); }
            public Flux<MemoryEntry> retrieve(MemoryRetrievalQuery q) { return Flux.empty(); }
            public Mono<MemoryEntry> get(String id) { return Mono.empty(); }
            public Mono<Void> update(String id, MemoryEntry e) { return Mono.empty(); }
            public Flux<MemoryEntry> recent(int n) { return Flux.empty(); }
            public Flux<MemoryEntry> range(long f, long t, int n) { return Flux.empty(); }
            public Mono<Void> forget(String id) { return Mono.empty(); }
            public Mono<Void> forgetByTenant(String t) { return Mono.empty(); }
            public Mono<Void> forgetByUser(String u) { return Mono.empty(); }
            public Mono<Void> forgetBySession(String s) { return Mono.empty(); }
            public Mono<Void> clear() { return Mono.empty(); }
        };
        MemoryEnrichmentHook hook = new MemoryEnrichmentHook(memory);
        assertEquals("MemoryEnrichment", hook.getName());
    }

    // ========================================================================
    // ToolAccessHook
    // ========================================================================

    @Test
    void testToolAccessHookAllow() {
        cd.lan1akea.core.tool.ToolAccessPolicy policy = new cd.lan1akea.core.tool.ToolAccessPolicy();
        policy.allow("t1", Set.of("calc", "search"));
        ToolAccessHook hook = new ToolAccessHook(policy);

        // calc 在 allowlist 中
        cd.lan1akea.core.tool.ToolCallContext param =
            cd.lan1akea.core.tool.ToolCallContext.of("c1", "calc", Map.of());
        HookEvent event = new HookEvent(HookEventType.PRE_TOOL_CALL);
        event.setCallParam(param);
        HookResult r = hook.onEvent(event,
            new HookContext("a", "t1", null, null, 0, null, null)).block();

        assertTrue(r.isContinue());
    }

    @Test
    void testToolAccessHookDeny() {
        cd.lan1akea.core.tool.ToolAccessPolicy policy = new cd.lan1akea.core.tool.ToolAccessPolicy();
        policy.allow("t1", Set.of("calc"));
        ToolAccessHook hook = new ToolAccessHook(policy);

        // weather 不在 allowlist 中
        cd.lan1akea.core.tool.ToolCallContext param =
            cd.lan1akea.core.tool.ToolCallContext.of("c1", "weather", Map.of());
        HookEvent event = new HookEvent(HookEventType.PRE_TOOL_CALL);
        event.setCallParam(param);
        HookResult r = hook.onEvent(event,
            new HookContext("a", "t1", null, null, 0, null, null)).block();

        assertTrue(r.isAbort());
        assertTrue(r.getAbortReason().contains("weather"));
    }

    @Test
    void testToolAccessHookBlocklist() {
        cd.lan1akea.core.tool.ToolAccessPolicy policy = new cd.lan1akea.core.tool.ToolAccessPolicy();
        policy.block("t1", Set.of("dangerous"));
        ToolAccessHook hook = new ToolAccessHook(policy);

        // safe_tool 不在 blocklist
        HookEvent event1 = new HookEvent(HookEventType.PRE_TOOL_CALL);
        event1.setCallParam(cd.lan1akea.core.tool.ToolCallContext.of("c1", "safe_tool", Map.of()));
        assertTrue(hook.onEvent(event1,
            new HookContext("a", "t1", null, null, 0, null, null)).block().isContinue());

        // dangerous 在 blocklist
        HookEvent event2 = new HookEvent(HookEventType.PRE_TOOL_CALL);
        event2.setCallParam(cd.lan1akea.core.tool.ToolCallContext.of("c2", "dangerous", Map.of()));
        assertTrue(hook.onEvent(event2,
            new HookContext("a", "t1", null, null, 0, null, null)).block().isAbort());
    }

    @Test
    void testToolAccessHookDefaultAllow() {
        cd.lan1akea.core.tool.ToolAccessPolicy policy = new cd.lan1akea.core.tool.ToolAccessPolicy();
        ToolAccessHook hook = new ToolAccessHook(policy);

        HookEvent event = new HookEvent(HookEventType.PRE_TOOL_CALL);
        event.setCallParam(cd.lan1akea.core.tool.ToolCallContext.of("c1", "any_tool", Map.of()));
        HookResult r = hook.onEvent(event,
            new HookContext("a", "t1", null, null, 0, null, null)).block();

        assertTrue(r.isContinue());
    }
}
