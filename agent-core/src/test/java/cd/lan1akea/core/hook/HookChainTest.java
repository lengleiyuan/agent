package cd.lan1akea.core.hook;
import java.util.Set;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;


import static org.junit.jupiter.api.Assertions.*;

/**
 * Hook 链单元测试。
 * 验证 Hook 排序、链式执行、ABORT/INTERRUPT 短路、多事件类型匹配。
 */
class HookChainTest {

    @Test
    void testHookOrderingByPriority() {
        HookChain chain = new HookChain();
        TrackingHook low = new TrackingHook("low", HookEventType.PRE_REASONING, 100);
        TrackingHook high = new TrackingHook("high", HookEventType.PRE_REASONING, 1);
        chain.register(low);
        chain.register(high);

        // high priority (1) should execute before low priority (100)
        chain.fire(HookEventType.PRE_REASONING, new HookEvent(HookEventType.PRE_REASONING),
            new HookContext("test", null, null, null, 0, null, null)).block();

        assertTrue(high.executedBefore(low), "高优先级 Hook 应先执行");
    }

    @Test
    void testContinueChaining() {
        HookChain chain = new HookChain();
        TrackingHook h1 = new TrackingHook("h1", HookEventType.PRE_REASONING, 1);
        TrackingHook h2 = new TrackingHook("h2", HookEventType.PRE_REASONING, 2);
        chain.register(h1);
        chain.register(h2);

        HookResult result = chain.fire(HookEventType.PRE_REASONING,
            new HookEvent(HookEventType.PRE_REASONING),
            new HookContext("test", null, null, null, 0, null, null)).block();

        assertNotNull(result);
        assertTrue(result.isContinue());
        assertTrue(h1.wasExecuted());
        assertTrue(h2.wasExecuted());
    }

    @Test
    void testAbortStopsChain() {
        HookChain chain = new HookChain();
        TrackingHook h1 = new TrackingHook("h1", HookEventType.PRE_REASONING, 1);
        AbortHook abort = new AbortHook("abort", HookEventType.PRE_REASONING, 2, "test abort");
        TrackingHook h2 = new TrackingHook("h2", HookEventType.PRE_REASONING, 3);
        chain.register(h1);
        chain.register(abort);
        chain.register(h2);

        HookResult result = chain.fire(HookEventType.PRE_REASONING,
            new HookEvent(HookEventType.PRE_REASONING),
            new HookContext("test", null, null, null, 0, null, null)).block();

        assertNotNull(result);
        assertTrue(result.isAbort());
        assertTrue(h1.wasExecuted());
        assertFalse(h2.wasExecuted(), "ABORT 后不应执行后续 Hook");
    }

    @Test
    void testInterruptStopsChain() {
        HookChain chain = new HookChain();
        TrackingHook h1 = new TrackingHook("h1", HookEventType.PRE_REASONING, 1);
        InterruptHook interrupt = new InterruptHook("interrupt", HookEventType.PRE_REASONING, 2);
        TrackingHook h2 = new TrackingHook("h2", HookEventType.PRE_REASONING, 3);
        chain.register(h1);
        chain.register(interrupt);
        chain.register(h2);

        HookResult result = chain.fire(HookEventType.PRE_REASONING,
            new HookEvent(HookEventType.PRE_REASONING),
            new HookContext("test", null, null, null, 0, null, null)).block();

        assertNotNull(result);
        assertTrue(result.isInterrupt());
        assertTrue(h1.wasExecuted());
        assertFalse(h2.wasExecuted(), "INTERRUPT 后不应执行后续 Hook");
    }

    @Test
    void testSkipUnmatchedEventType() {
        HookChain chain = new HookChain();
        TrackingHook h1 = new TrackingHook("h1", HookEventType.PRE_REASONING, 1);
        chain.register(h1);

        HookResult result = chain.fire(HookEventType.POST_REASONING,
            new HookEvent(HookEventType.POST_REASONING),
            new HookContext("test", null, null, null, 0, null, null)).block();

        assertTrue(result.isContinue());
        assertFalse(h1.wasExecuted(), "不匹配的事件类型应跳过");
    }

    @Test
    void testMultiEventTypes() {
        HookChain chain = new HookChain();
        MultiEventHook hook = new MultiEventHook("multi", Set.of(
            HookEventType.PRE_REASONING, HookEventType.POST_REASONING, HookEventType.PRE_TOOL_CALL));
        chain.register(hook);

        // PRE_REASONING should match
        HookResult r1 = chain.fire(HookEventType.PRE_REASONING,
            new HookEvent(HookEventType.PRE_REASONING),
            new HookContext("test", null, null, null, 0, null, null)).block();
        assertTrue(r1.isContinue());
        assertTrue(hook.wasExecuted());

        hook.reset();
        // PRE_TOOL_CALL should match
        HookResult r2 = chain.fire(HookEventType.PRE_TOOL_CALL,
            new HookEvent(HookEventType.PRE_TOOL_CALL),
            new HookContext("test", null, null, null, 0, null, null)).block();
        assertTrue(r2.isContinue());
        assertTrue(hook.wasExecuted());

        hook.reset();
        // AFTER_ITERATION should NOT match (hook only subscribes to PRE_TOOL / POST_TOOL)
        HookResult r3 = chain.fire(HookEventType.AFTER_ITERATION,
            new HookEvent(HookEventType.AFTER_ITERATION),
            new HookContext("test", null, null, null, 0, null, null)).block();
        assertTrue(r3.isContinue());
        assertFalse(hook.wasExecuted());
    }

    @Test
    void testEmptyChainReturnsContinue() {
        HookChain chain = new HookChain();
        HookResult result = chain.fire(HookEventType.PRE_REASONING,
            new HookEvent(HookEventType.PRE_REASONING),
            new HookContext("test", null, null, null, 0, null, null)).block();

        assertNotNull(result);
        assertTrue(result.isContinue());
    }

    @Test
    void testModifyResultPropagation() {
        HookChain chain = new HookChain();
        chain.register(new ModifyHook("mod", HookEventType.PRE_REASONING, 1, "modified-data"));

        HookResult result = chain.fire(HookEventType.PRE_REASONING,
            new HookEvent(HookEventType.PRE_REASONING),
            new HookContext("test", null, null, null, 0, null, null)).block();

        assertNotNull(result);
        assertTrue(result.isModify());
        assertEquals("modified-data", result.getModifiedData());
    }

    @Test
    void testUnregister() {
        HookChain chain = new HookChain();
        TrackingHook h1 = new TrackingHook("h1", HookEventType.PRE_REASONING, 1);
        chain.register(h1);
        chain.unregister("h1");

        HookResult result = chain.fire(HookEventType.PRE_REASONING,
            new HookEvent(HookEventType.PRE_REASONING),
            new HookContext("test", null, null, null, 0, null, null)).block();

        assertTrue(result.isContinue());
        assertFalse(h1.wasExecuted());
    }

    // ========================================================================
    // Test Hook implementations
    // ========================================================================

    static class TrackingHook implements Hook {
        private final String name;
        private final HookEventType eventType;
        private final int priority;
        private boolean executed;
        private long execTime;

        TrackingHook(String name, HookEventType eventType, int priority) {
            this.name = name;
            this.eventType = eventType;
            this.priority = priority;
        }

        @Override public String getName() { return name; }
        @Override public Set<HookEventType> getSubscribedEventTypes() { return Set.of(eventType); }
        @Override public int getPriority() { return priority; }

        @Override
        public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
            executed = true;
            execTime = System.nanoTime();
            return Mono.just(HookResult.continue_());
        }

        boolean wasExecuted() { return executed; }
        boolean executedBefore(TrackingHook other) { return execTime < other.execTime; }
    }

    static class AbortHook implements Hook {
        private final String name;
        private final HookEventType eventType;
        private final int priority;
        private final String reason;

        AbortHook(String name, HookEventType eventType, int priority, String reason) {
            this.name = name; this.eventType = eventType; this.priority = priority; this.reason = reason;
        }

        @Override public String getName() { return name; }
        @Override public Set<HookEventType> getSubscribedEventTypes() { return Set.of(eventType); }
        @Override public int getPriority() { return priority; }

        @Override
        public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
            return Mono.just(HookResult.abort(reason));
        }
    }

    static class InterruptHook implements Hook {
        private final String name;
        private final HookEventType eventType;
        private final int priority;

        InterruptHook(String name, HookEventType eventType, int priority) {
            this.name = name; this.eventType = eventType; this.priority = priority;
        }

        @Override public String getName() { return name; }
        @Override public Set<HookEventType> getSubscribedEventTypes() { return Set.of(eventType); }
        @Override public int getPriority() { return priority; }

        @Override
        public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
            return Mono.just(HookResult.interrupt("needs human"));
        }
    }

    static class ModifyHook implements Hook {
        private final String name;
        private final HookEventType eventType;
        private final int priority;
        private final Object data;

        ModifyHook(String name, HookEventType eventType, int priority, Object data) {
            this.name = name; this.eventType = eventType; this.priority = priority; this.data = data;
        }

        @Override public String getName() { return name; }
        @Override public Set<HookEventType> getSubscribedEventTypes() { return Set.of(eventType); }
        @Override public int getPriority() { return priority; }

        @Override
        public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
            return Mono.just(HookResult.modify(data));
        }
    }

    static class MultiEventHook implements Hook {
        private final String name;
        private final Set<HookEventType> types;
        private boolean executed;

        MultiEventHook(String name, Set<HookEventType> types) { this.name = name; this.types = types; }

        @Override public String getName() { return name; }
        @Override public Set<HookEventType> getSubscribedEventTypes() { return types; }

        @Override
        public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
            executed = true;
            return Mono.just(HookResult.continue_());
        }

        boolean wasExecuted() { return executed; }
        void reset() { executed = false; }
    }
}
