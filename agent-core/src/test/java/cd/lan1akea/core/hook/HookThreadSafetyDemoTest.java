package cd.lan1akea.core.hook;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hook 线程安全——可复现的并发 Bug 演示。
 *
 * <p>场景：一个"推理耗时统计" Hook，PRE_REASONING 记开始时间，
 * POST_REASONING 算耗时。这是最典型的跨 PRE/POST 状态传递需求。</p>
 */
class HookThreadSafetyDemoTest {

    // ========================================================================
    // ❌ 错误实现：HashMap 存 per-request 状态
    // ========================================================================

    static class BrokenHashMapHook implements Hook {
        private final Map<String, Long> startTimes = new HashMap<>();
        private final AtomicInteger postMatches = new AtomicInteger(0);

        @Override public String getName() { return "broken-hashmap"; }
        @Override public Set<HookEventType> getSubscribedEventTypes() {
            return Set.of(HookEventType.PRE_REASONING, HookEventType.POST_REASONING);
        }

        @Override
        public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
            String key = context.getSessionId() + "-" + context.getCurrentIteration();
            if (event.getHookEventType() == HookEventType.PRE_REASONING) {
                startTimes.put(key, System.nanoTime());
            } else {
                Long start = startTimes.remove(key);
                if (start != null) postMatches.incrementAndGet();
            }
            return Mono.just(HookResult.continue_());
        }
        int getMatches() { return postMatches.get(); }
    }

    // ========================================================================
    // ✅ ConcurrentHashMap —— 集合操作安全，但 key 可能逻辑冲突
    // ========================================================================

    static class SafeConcurrentHook implements Hook {
        private final ConcurrentMap<String, Long> startTimes = new ConcurrentHashMap<>();
        private final AtomicInteger postMatches = new AtomicInteger(0);

        @Override public String getName() { return "safe-concurrent"; }
        @Override public Set<HookEventType> getSubscribedEventTypes() {
            return Set.of(HookEventType.PRE_REASONING, HookEventType.POST_REASONING);
        }

        @Override
        public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
            String key = context.getSessionId() + "-" + context.getCurrentIteration();
            if (event.getHookEventType() == HookEventType.PRE_REASONING) {
                startTimes.put(key, System.nanoTime());
            } else {
                Long start = startTimes.remove(key);
                if (start != null) postMatches.incrementAndGet();
            }
            return Mono.just(HookResult.continue_());
        }
        int getMatches() { return postMatches.get(); }
    }

    // ========================================================================
    // ✅ HookEvent payload —— 零共享状态
    // ========================================================================

    static class PayloadPreHook implements Hook {
        @Override public String getName() { return "payload-pre"; }
        @Override public Set<HookEventType> getSubscribedEventTypes() {
            return Set.of(HookEventType.PRE_REASONING);
        }
        @Override
        public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
            event.setPayload("timing_ns", System.nanoTime());
            return Mono.just(HookResult.continue_());
        }
    }

    static class PayloadPostHook implements Hook {
        private final AtomicInteger matches = new AtomicInteger(0);
        @Override public String getName() { return "payload-post"; }
        @Override public Set<HookEventType> getSubscribedEventTypes() {
            return Set.of(HookEventType.POST_REASONING);
        }
        @Override
        public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
            Long start = event.getPayload("timing_ns");
            if (start != null) {
                long elapsed = System.nanoTime() - start;
                matches.incrementAndGet();
            }
            return Mono.just(HookResult.continue_());
        }
        int getMatches() { return matches.get(); }
    }


    // ========================================================================
    // 测试 1：HashMap —— 并发 put/remove 导致数据损坏
    // ========================================================================

    @Test
    void testHashMapFailsUnderConcurrency() throws InterruptedException {
        BrokenHashMapHook hook = new BrokenHashMapHook();
        HookChain chain = new HookChain();
        chain.register(hook);

        int threads = 20, iters = 200, expected = threads * iters;
        CountDownLatch latch = new CountDownLatch(threads);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            new Thread(() -> {
                try {
                    for (int i = 0; i < iters; i++) {
                        // 每个线程用唯一的 sessionId，杜绝 key 层面的逻辑冲突
                        String sid = "s-" + tid;
                        HookContext ctx = new HookContext("a", "t", sid, "u", i, null, null);
                        chain.fire(HookEventType.PRE_REASONING,
                            new HookEvent(HookEventType.PRE_REASONING), ctx).block();
                        chain.fire(HookEventType.POST_REASONING,
                            new HookEvent(HookEventType.POST_REASONING), ctx).block();
                    }
                } catch (Throwable e) {
                    failures.add(e);
                } finally {
                    latch.countDown();
                }
            }, "hashmap-" + t).start();
        }

        latch.await(10, TimeUnit.SECONDS);

        System.out.println("=== HashMap 结果 ===");
        System.out.println("异常: " + failures.size()
            + (failures.isEmpty() ? "" : " 第一个=" + failures.get(0).toString()));
        System.out.println("匹配: " + hook.getMatches() + " / " + expected
            + " (丢失: " + (expected - hook.getMatches()) + ")");

        // HashMap 并发 put/remove 至少会导致数据丢失
        // （运气好时只丢数据不崩，但绝不会 100% 正确）
        assertTrue(failures.size() > 0 || hook.getMatches() < expected,
            "HashMap 并发必定丢数据或崩。" +
            " 异常=" + failures.size() + " 匹配=" + hook.getMatches() + "/" + expected);
    }

    // ========================================================================
    // 测试 2：ConcurrentHashMap —— 集合操作安全，但 key 冲突仍是问题
    // ========================================================================

    @Test
    void testConcurrentHashMapSurvivesWithUniqueKeys() throws InterruptedException {
        SafeConcurrentHook hook = new SafeConcurrentHook();
        HookChain chain = new HookChain();
        chain.register(hook);

        int threads = 20, iters = 200, expected = threads * iters;
        CountDownLatch latch = new CountDownLatch(threads);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            new Thread(() -> {
                try {
                    for (int i = 0; i < iters; i++) {
                        String sid = "s-" + tid;  // 每个线程独立的 sessionId
                        HookContext ctx = new HookContext("a", "t", sid, "u", i, null, null);
                        chain.fire(HookEventType.PRE_REASONING,
                            new HookEvent(HookEventType.PRE_REASONING), ctx).block();
                        chain.fire(HookEventType.POST_REASONING,
                            new HookEvent(HookEventType.POST_REASONING), ctx).block();
                    }
                } catch (Throwable e) {
                    failures.add(e);
                } finally {
                    latch.countDown();
                }
            }, "concurrent-" + t).start();
        }

        latch.await(10, TimeUnit.SECONDS);

        System.out.println("=== ConcurrentHashMap (唯一key) 结果 ===");
        System.out.println("匹配: " + hook.getMatches() + " / " + expected);

        assertTrue(failures.isEmpty(), "不应有异常: " + failures.size());
        assertEquals(expected, hook.getMatches(), "所有 POST 应匹配到 PRE");
    }

    /**
     * 演示 ConcurrentHashMap 的 key 冲突问题：如果多个线程共享相同的 key，
     * 线程 B 的 PRE 会覆盖线程 A 的 PRE，导致 A 的 POST 匹配丢失。
     * 这不是线程安全 bug，而是 key 设计的逻辑问题。
     */
    @Test
    void testConcurrentHashMapKeyCollisionCausesLogicalDataLoss() throws InterruptedException {
        SafeConcurrentHook hook = new SafeConcurrentHook();
        HookChain chain = new HookChain();
        chain.register(hook);

        int threads = 10;
        CountDownLatch latch = new CountDownLatch(threads);
        String sharedSession = "shared-session";

        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try {
                    for (int i = 0; i < 100; i++) {
                        // 所有线程用同一个 sessionId + 同一个 iteration → key 碰撞
                        HookContext ctx = new HookContext("a", "t", sharedSession, "u", i, null, null);
                        chain.fire(HookEventType.PRE_REASONING,
                            new HookEvent(HookEventType.PRE_REASONING), ctx).block();
                        chain.fire(HookEventType.POST_REASONING,
                            new HookEvent(HookEventType.POST_REASONING), ctx).block();
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await(10, TimeUnit.SECONDS);

        int expected = threads * 100;
        System.out.println("=== ConcurrentHashMap (共享key, key冲突) 结果 ===");
        System.out.println("匹配: " + hook.getMatches() + " / " + expected
            + " (丢失: " + (expected - hook.getMatches()) + ")");

        // 共享 key 导致覆盖——post 拿到的不是自己的 startTime
        assertTrue(hook.getMatches() < expected,
            "共享 key 应导致数据丢失。匹配=" + hook.getMatches() + "/" + expected);
    }

    // ========================================================================
    // 测试 3：HookEvent payload —— 零共享状态，天然线程安全
    // ========================================================================

    @Test
    void testPayloadBasedNoSharedState() throws InterruptedException {
        PayloadPreHook pre = new PayloadPreHook();
        PayloadPostHook post = new PayloadPostHook();

        HookChain chain = new HookChain();
        chain.register(pre);
        chain.register(post);

        int threads = 20, iters = 200, expected = threads * iters;
        CountDownLatch latch = new CountDownLatch(threads);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try {
                    for (int i = 0; i < iters; i++) {
                        HookContext ctx = new HookContext("a", "t", "s", "u", i, null, null);

                        // PRE 写入 event payload
                        HookEvent preEvent = new HookEvent(HookEventType.PRE_REASONING);
                        chain.fire(HookEventType.PRE_REASONING, preEvent, ctx).block();

                        // POST 从 event payload 读取——手动传递
                        HookEvent postEvent = new HookEvent(HookEventType.POST_REASONING);
                        postEvent.setPayload("timing_ns", preEvent.getPayload("timing_ns"));
                        chain.fire(HookEventType.POST_REASONING, postEvent, ctx).block();
                    }
                } catch (Throwable e) {
                    failures.add(e);
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await(10, TimeUnit.SECONDS);

        System.out.println("=== HookEvent payload 结果 ===");
        System.out.println("匹配: " + post.getMatches() + " / " + expected);

        assertTrue(failures.isEmpty(), "不应有异常: " + failures.size());
        assertEquals(expected, post.getMatches());
    }
}
