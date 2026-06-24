package cd.lan1akea.core.hook;
import java.util.Set;

import cd.lan1akea.core.hook.recorder.HookRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;

class HookDispatcherTest {

    private HookChain chain;
    private HookDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        chain = new HookChain();
        dispatcher = new HookDispatcher(chain);
    }

    @Test
    void testDispatchToMatchingHook() {
        TrackingHook hook = new TrackingHook("h1", HookEventType.PRE_REASONING);
        chain.register(hook);

        HookResult result = dispatcher.dispatch(HookEventType.PRE_REASONING,
            new HookEvent(HookEventType.PRE_REASONING),
            new HookContext("test", null, null, null, 0, null, null)).block();

        assertTrue(result.isContinue());
        assertTrue(hook.wasExecuted());
    }

    @Test
    void testDispatchSkipsNonMatchingHook() {
        TrackingHook hook = new TrackingHook("h1", HookEventType.PRE_REASONING);
        chain.register(hook);

        dispatcher.dispatch(HookEventType.POST_REASONING,
            new HookEvent(HookEventType.POST_REASONING),
            new HookContext("test", null, null, null, 0, null, null)).block();

        assertFalse(hook.wasExecuted());
    }

    @Test
    void testEmptyChainReturnsContinue() {
        HookResult result = dispatcher.dispatch(HookEventType.PRE_REASONING,
            new HookEvent(HookEventType.PRE_REASONING),
            new HookContext("test", null, null, null, 0, null, null)).block();

        assertTrue(result.isContinue());
    }

    @Test
    void testAbortPropagates() {
        chain.register(new AbortHook("abort", HookEventType.PRE_REASONING, "stop"));

        HookResult result = dispatcher.dispatch(HookEventType.PRE_REASONING,
            new HookEvent(HookEventType.PRE_REASONING),
            new HookContext("test", null, null, null, 0, null, null)).block();

        assertTrue(result.isAbort());
        assertEquals("stop", result.getAbortReason());
    }

    @Test
    void testRecorderCapturesEvents() {
        HookRecorder recorder = new HookRecorder();
        dispatcher.setRecorder(recorder);

        chain.register(new TrackingHook("h1", HookEventType.PRE_REASONING));
        dispatcher.dispatch(HookEventType.PRE_REASONING,
            new HookEvent(HookEventType.PRE_REASONING),
            new HookContext("test", null, null, null, 0, null, null)).block();

        assertEquals(1, recorder.size());
        assertEquals("dispatcher", recorder.getEntries().get(0).getHookName());
    }

    @Test
    void testRecorderDisabledDoesNotRecord() {
        HookRecorder recorder = new HookRecorder();
        recorder.setEnabled(false);
        dispatcher.setRecorder(recorder);

        chain.register(new TrackingHook("h1", HookEventType.PRE_REASONING));
        dispatcher.dispatch(HookEventType.PRE_REASONING,
            new HookEvent(HookEventType.PRE_REASONING),
            new HookContext("test", null, null, null, 0, null, null)).block();

        assertEquals(0, recorder.size());
    }

    @Test
    void testRecorderClear() {
        HookRecorder recorder = new HookRecorder();
        dispatcher.setRecorder(recorder);
        chain.register(new TrackingHook("h1", HookEventType.PRE_REASONING));

        dispatcher.dispatch(HookEventType.PRE_REASONING,
            new HookEvent(HookEventType.PRE_REASONING),
            new HookContext("test", null, null, null, 0, null, null)).block();

        recorder.clear();
        assertEquals(0, recorder.size());
    }

    @Test
    void testGetHookChain() {
        assertSame(chain, dispatcher.getHookChain());
    }

    @Test
    void testRuntimeContextAwareInjection() {
        ContextAwareHook hook = new ContextAwareHook("aware", HookEventType.PRE_REASONING);
        chain.register(hook);

        HookContext ctx = new HookContext("agent1", "t1", "s1", "u1", 3, null, null);
        dispatcher.dispatch(HookEventType.PRE_REASONING,
            new HookEvent(HookEventType.PRE_REASONING), ctx).block();

        assertNotNull(hook.getReceivedContext());
        assertEquals("agent1", hook.getReceivedContext().getAgentName());
    }

    // ========================================================================
    // Test hooks
    // ========================================================================

    static class TrackingHook implements Hook {
        private final String name;
        private final HookEventType eventType;
        private boolean executed;

        TrackingHook(String name, HookEventType eventType) { this.name = name; this.eventType = eventType; }

        @Override public String getName() { return name; }
        @Override public Set<HookEventType> getSubscribedEventTypes() { return Set.of(eventType); }

        @Override
        public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
            executed = true;
            return Mono.just(HookResult.continue_());
        }

        boolean wasExecuted() { return executed; }
    }

    static class AbortHook implements Hook {
        private final String name;
        private final HookEventType eventType;
        private final String reason;

        AbortHook(String name, HookEventType eventType, String reason) {
            this.name = name; this.eventType = eventType; this.reason = reason;
        }

        @Override public String getName() { return name; }
        @Override public Set<HookEventType> getSubscribedEventTypes() { return Set.of(eventType); }

        @Override
        public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
            return Mono.just(HookResult.abort(reason));
        }
    }

    static class ContextAwareHook implements Hook, RuntimeContextAware {
        private final String name;
        private final HookEventType eventType;
        private HookContext receivedContext;

        ContextAwareHook(String name, HookEventType eventType) { this.name = name; this.eventType = eventType; }

        @Override public String getName() { return name; }
        @Override public Set<HookEventType> getSubscribedEventTypes() { return Set.of(eventType); }
        @Override public void setRuntimeContext(HookContext ctx) { this.receivedContext = ctx; }
        HookContext getReceivedContext() { return receivedContext; }

        @Override
        public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
            return Mono.just(HookResult.continue_());
        }
    }
}
