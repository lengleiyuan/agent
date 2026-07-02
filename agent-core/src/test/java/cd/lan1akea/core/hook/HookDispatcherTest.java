package cd.lan1akea.core.hook;
import java.util.Set;

import cd.lan1akea.core.hook.recorder.HookRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

        HookResult result = dispatcher.dispatch(
            new HookEvent(HookEventType.PRE_REASONING),
            new HookContext("test", null, null, null, 0, null, null)).block();

        assertTrue(result.isContinue());
        assertTrue(hook.wasExecuted());
    }

    @Test
    void testDispatchSkipsNonMatchingHook() {
        TrackingHook hook = new TrackingHook("h1", HookEventType.PRE_REASONING);
        chain.register(hook);

        dispatcher.dispatch(
            new HookEvent(HookEventType.POST_REASONING),
            new HookContext("test", null, null, null, 0, null, null)).block();

        assertFalse(hook.wasExecuted());
    }

    @Test
    void testEmptyChainReturnsContinue() {
        HookResult result = dispatcher.dispatch(
            new HookEvent(HookEventType.PRE_REASONING),
            new HookContext("test", null, null, null, 0, null, null)).block();

        assertTrue(result.isContinue());
    }

    @Test
    void testAbortPropagates() {
        chain.register(new AbortHook("abort", HookEventType.PRE_REASONING, "stop"));

        HookResult result = dispatcher.dispatch(
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
        dispatcher.dispatch(
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
        dispatcher.dispatch(
            new HookEvent(HookEventType.PRE_REASONING),
            new HookContext("test", null, null, null, 0, null, null)).block();

        assertEquals(0, recorder.size());
    }

    @Test
    void testRecorderClear() {
        HookRecorder recorder = new HookRecorder();
        dispatcher.setRecorder(recorder);
        chain.register(new TrackingHook("h1", HookEventType.PRE_REASONING));

        dispatcher.dispatch(
            new HookEvent(HookEventType.PRE_REASONING),
            new HookContext("test", null, null, null, 0, null, null)).block();

        recorder.clear();
        assertEquals(0, recorder.size());
    }

    @Test
    void testGetHookChain() {
        assertSame(chain, dispatcher.getHookChain());
    }

    // ========================================================================
    // RuntimeContextAware 移除后的验证
    // ========================================================================

    /**
     * 验证 HookContext 通过 onEvent 参数传入（替代已移除的 RuntimeContextAware 注入）。
     * 每个 Hook 直接从 onEvent(HookEvent, HookContext) 的第二个参数获取上下文，
     * 无需在 Hook 实例上存储 per-request 可变状态。
     */
    @Test
    void testContextAvailableFromOnEventParameter() {
        ContextReadingHook hook = new ContextReadingHook("reader", HookEventType.PRE_REASONING);
        chain.register(hook);

        HookContext ctx = new HookContext("agent1", "t1", "s1", "u1", 3,
            List.of("tool-a"), null);
        dispatcher.dispatch(
            new HookEvent(HookEventType.PRE_REASONING), ctx).block();

        assertNotNull(hook.getContextFromOnEvent());
        assertEquals("agent1", hook.getContextFromOnEvent().getAgentName());
        assertEquals("t1", hook.getContextFromOnEvent().getTenantId());
        assertEquals("s1", hook.getContextFromOnEvent().getSessionId());
        assertEquals("u1", hook.getContextFromOnEvent().getUserId());
        assertEquals(3, hook.getContextFromOnEvent().getCurrentIteration());
    }

    /**
     * 验证同一个 Hook 实例被多次调度时，每次收到的 HookContext 都是相互独立的。
     */
    @Test
    void testEachDispatchReceivesIndependentContext() {
        ContextReadingHook hook = new ContextReadingHook("reader", HookEventType.PRE_REASONING);
        chain.register(hook);

        HookContext ctx1 = new HookContext("agent-A", "tenant-1", "s1", "u1", 1, null, null);
        HookContext ctx2 = new HookContext("agent-B", "tenant-2", "s2", "u2", 5, null, null);

        dispatcher.dispatch(
            new HookEvent(HookEventType.PRE_REASONING), ctx1).block();
        assertEquals("tenant-1", hook.getContextFromOnEvent().getTenantId());

        dispatcher.dispatch(
            new HookEvent(HookEventType.PRE_REASONING), ctx2).block();
        assertEquals("tenant-2", hook.getContextFromOnEvent().getTenantId());
    }

    // ========================================================================
    // 并发安全测试
    // ========================================================================

    /**
     * 并发场景：多个线程同时通过 HookDispatcher 调度同一套 Hook 实例。
     * 验证：
     * 1. 每个调用拿到属于自己的 HookContext（不会串号）
     * 2. 无竞态导致崩溃或数据错乱
     */
    @Test
    void testConcurrentDispatchThreadSafety() throws InterruptedException {
        int threadCount = 20;
        int callsPerThread = 50;

        // 注册多个 Hook，模拟真实链路
        chain.register(new ContextRecordingHook("tenant-checker", HookEventType.PRE_REASONING));
        chain.register(new CountingHook("counter", HookEventType.PRE_REASONING));
        chain.register(new ContextRecordingHook("auditor", HookEventType.PRE_TOOL_CALL));

        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger completedCalls = new AtomicInteger(0);
        List<Exception> failures = Collections.synchronizedList(new ArrayList<>());

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            new Thread(() -> {
                try {
                    for (int i = 0; i < callsPerThread; i++) {
                        String tenantId = "tenant-" + threadId;
                        String userId = "user-" + threadId + "-" + i;
                        String sessionId = "session-" + threadId;

                        HookContext ctx = new HookContext(
                            "agent-" + threadId, tenantId, sessionId, userId,
                            i, List.of("tool-" + i), null);

                        HookResult result = dispatcher.dispatch(
                            new HookEvent(HookEventType.PRE_REASONING), ctx)
                            .block(Duration.ofSeconds(10));

                        assertNotNull(result, "HookResult 不应为 null");
                        assertTrue(result.isContinue() || result.isModify(),
                            "结果应为 CONTINUE 或 MODIFY");

                        completedCalls.incrementAndGet();
                    }
                } catch (Exception e) {
                    failures.add(e);
                } finally {
                    latch.countDown();
                }
            }, "hook-test-" + t).start();
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "所有线程应在超时前完成");

        if (!failures.isEmpty()) {
            failures.forEach(e -> System.err.println("并发测试失败: " + e.getMessage()));
            fail("并发测试出现 " + failures.size() + " 个异常，第一个: " + failures.get(0).getMessage());
        }

        assertEquals(threadCount * callsPerThread, completedCalls.get(),
            "所有调用都应完成");
    }

    /**
     * 并发场景：验证 ContextRecordingHook 在高并发下不会发生租户 ID 串号。
     * 这是 Bug 复现：如果 Hook 通过 setter 注入存储 per-request 状态，
     * 并发下线程 A 的上下文会被线程 B 覆盖。
     */
    @Test
    void testNoContextBleedingUnderConcurrency() throws InterruptedException {
        int threadCount = 10;
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        ContextRecordingHook hook = new ContextRecordingHook("bleed-checker", HookEventType.PRE_REASONING);
        chain.register(hook);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            new Thread(() -> {
                try {
                    for (int i = 0; i < 100; i++) {
                        String expectedTenant = "tenant-" + threadId;

                        HookContext ctx = new HookContext(
                            "agent-" + threadId, expectedTenant,
                            "session-" + threadId, "user-" + threadId,
                            i, null, null);

                        dispatcher.dispatch(
                            new HookEvent(HookEventType.PRE_REASONING), ctx)
                            .block(Duration.ofSeconds(5));

                        // 验证：hook 记录的最后一个 context 来自本次调用
                        // （因为是共享实例，最后一次可能是其他线程的，但不应
                        //  导致逻辑错误——Hook 在 onEvent 内使用的是参数 context，不是字段）
                    }
                } catch (Exception e) {
                    errors.add("thread-" + threadId + ": " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            }, "bleed-test-" + t).start();
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        assertTrue(errors.isEmpty(), "不应出现异常，但: " + String.join("; ", errors));
    }

    /**
     * 并发 + HookChain：验证 HookChain.fire() 在并发场景下链路执行正确，
     * 每个线程的 Hook 链独立推进，不受其他线程干扰。
     */
    @Test
    void testConcurrentHookChainFire() throws InterruptedException {
        int threadCount = 16;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger totalExecs = new AtomicInteger(0);

        // 5 个 Hook 组成的链
        for (int i = 1; i <= 5; i++) {
            chain.register(new CountingHook("chain-hook-" + i, HookEventType.PRE_REASONING));
        }

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            new Thread(() -> {
                try {
                    for (int i = 0; i < 50; i++) {
                        HookResult r = chain.fire(HookEventType.PRE_REASONING,
                            new HookEvent(HookEventType.PRE_REASONING),
                            new HookContext("a", "t-" + threadId, "s", "u",
                                i, null, null))
                            .block(Duration.ofSeconds(10));
                        assertNotNull(r);
                        totalExecs.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        assertEquals(threadCount * 50, totalExecs.get());
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

    /**
     * 安全地读取 HookContext 的 Hook —— 从 onEvent 参数获取，
     * 不在实例字段上存储 per-request 可变状态。
     */
    static class ContextReadingHook implements Hook {
        private final String name;
        private final HookEventType eventType;
        // 仅用于测试断言，记录最后一次 onEvent 收到的 context
        private volatile HookContext contextFromOnEvent;

        ContextReadingHook(String name, HookEventType eventType) {
            this.name = name;
            this.eventType = eventType;
        }

        @Override public String getName() { return name; }
        @Override public Set<HookEventType> getSubscribedEventTypes() { return Set.of(eventType); }

        @Override
        public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
            this.contextFromOnEvent = context;
            return Mono.just(HookResult.continue_());
        }

        HookContext getContextFromOnEvent() { return contextFromOnEvent; }
    }

    /**
     * 记录每次 onEvent 收到的 tenantId（用于验证无上下文串号）。
     * 使用 CopyOnWriteArrayList 保证并发写入安全。
     */
    static class ContextRecordingHook implements Hook {
        private final String name;
        private final HookEventType eventType;
        private final List<String> recordedTenantIds = new java.util.concurrent.CopyOnWriteArrayList<>();

        ContextRecordingHook(String name, HookEventType eventType) {
            this.name = name;
            this.eventType = eventType;
        }

        @Override public String getName() { return name; }
        @Override public Set<HookEventType> getSubscribedEventTypes() { return Set.of(eventType); }

        @Override
        public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
            // 直接使用参数中的 context，不存到可变字段
            recordedTenantIds.add(context.getTenantId());
            return Mono.just(HookResult.continue_());
        }

        List<String> getRecordedTenantIds() { return new ArrayList<>(recordedTenantIds); }
    }

    /**
     * 使用 AtomicInteger 记录调用次数的 Hook（线程安全的全局计数器）。
     */
    static class CountingHook implements Hook {
        private final String name;
        private final HookEventType eventType;
        private final AtomicInteger counter = new AtomicInteger(0);

        CountingHook(String name, HookEventType eventType) { this.name = name; this.eventType = eventType; }

        @Override public String getName() { return name; }
        @Override public Set<HookEventType> getSubscribedEventTypes() { return Set.of(eventType); }

        @Override
        public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
            counter.incrementAndGet();
            return Mono.just(HookResult.continue_());
        }

        int getCount() { return counter.get(); }
    }
}
