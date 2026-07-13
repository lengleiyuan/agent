package cd.lan1akea.core.hook;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AroundHook 洋葱包裹测试。
 */
class AroundHookChainTest {

    // ========================================================================
    // 基础功能
    // ========================================================================

    @Test
    void testEmptyChainPassesThrough() {
        AroundHookChain chain = new AroundHookChain();
        HookEvent event = new HookEvent(HookEventType.PRE_REASONING);

        HookEvent result = chain.aroundReasoning(event, null,
            e -> {
                e.setPayload("executed", true);
                return Mono.just(e);
            }).block();

        assertEquals(Boolean.TRUE, result.<Boolean>getPayload("executed"));
    }

    @Test
    void testSingleHookWrapsCore() {
        AroundHookChain chain = new AroundHookChain();
        chain.register(new OrderRecordingHook("A"));

        HookEvent event = new HookEvent(HookEventType.PRE_REASONING);
        // Core 也往共享列表写
        event.setPayload("_order", new ArrayList<String>());

        chain.aroundReasoning(event, null,
            e -> {
                OrderRecordingHook.append(e, "core");
                return Mono.just(e);
            }).block();

        @SuppressWarnings("unchecked")
        List<String> order = event.getPayload("_order");
        // A-before → core → A-after
        assertEquals(List.of("A-before", "core", "A-after"), order);
    }

    @Test
    void testMultipleHooksAreOnionLayered() {
        AroundHookChain chain = new AroundHookChain();
        chain.register(new OrderRecordingHook("A"));  // 外层
        chain.register(new OrderRecordingHook("B"));  // 内层

        HookEvent event = new HookEvent(HookEventType.PRE_REASONING);
        event.setPayload("_order", new ArrayList<String>());

        chain.aroundReasoning(event, null,
            e -> {
                OrderRecordingHook.append(e, "core");
                return Mono.just(e);
            }).block();

        @SuppressWarnings("unchecked")
        List<String> order = event.getPayload("_order");
        // A-before → B-before → core → B-after → A-after
        assertEquals(List.of("A-before", "B-before", "core", "B-after", "A-after"), order);
    }

    @Test
    void testHookCanModifyEventPayload() {
        AroundHookChain chain = new AroundHookChain();
        chain.register(new AroundHook() {
            @Override
            public String getName() { return "setter"; }

            @Override
            public Mono<HookEvent> aroundReasoning(HookEvent event, HookContext ctx,
                                                    Function<HookEvent, Mono<HookEvent>> next) {
                event.setPayload("before", "set-by-hook");
                return next.apply(event)
                    .map(e -> {
                        e.setPayload("after", "also-set-by-hook");
                        return e;
                    });
            }
        });

        HookEvent event = new HookEvent(HookEventType.PRE_REASONING);
        HookEvent result = chain.aroundReasoning(event, null,
            e -> {
                e.setPayload("core", "done");
                return Mono.just(e);
            }).block();

        assertEquals("set-by-hook", result.<String>getPayload("before"));
        assertEquals("done", result.<String>getPayload("core"));
        assertEquals("also-set-by-hook", result.<String>getPayload("after"));
    }

    @Test
    void testHookCanAccessContext() {
        AroundHookChain chain = new AroundHookChain();
        List<String> seen = new ArrayList<>();
        chain.register(new AroundHook() {
            @Override
            public String getName() { return "ctx-reader"; }

            @Override
            public Mono<HookEvent> aroundReasoning(HookEvent event, HookContext ctx,
                                                    Function<HookEvent, Mono<HookEvent>> next) {
                seen.add(ctx.getTenantId());
                seen.add(ctx.getSessionId());
                return next.apply(event);
            }
        });

        HookContext ctx = new HookContext("agent", "tenant-9", "session-42", "user-7", 0, null, null);
        chain.aroundReasoning(new HookEvent(HookEventType.PRE_REASONING), ctx,
            Mono::just).block();

        assertEquals("tenant-9", seen.get(0));
        assertEquals("session-42", seen.get(1));
    }

    @Test
    void testAllPhaseMethods() {
        AroundHookChain chain = new AroundHookChain();
        AtomicH hook = new AtomicH("all");
        chain.register(hook);

        HookContext ctx = new HookContext("a", "t", "s", "u", 0, null, null);
        HookEvent event = new HookEvent(HookEventType.PRE_REASONING);

        chain.aroundReasoning(event, ctx, Mono::just).block();
        assertEquals(1, hook.reasoningCount.get());

        chain.aroundToolCall(event, ctx, Mono::just).block();
        assertEquals(1, hook.toolCount.get());

        chain.aroundCall(event, ctx, Mono::just).block();
        assertEquals(1, hook.callCount.get());
    }

    @Test
    void testUnregister() {
        AroundHookChain chain = new AroundHookChain();
        OrderRecordingHook hook = new OrderRecordingHook("to-remove");
        chain.register(hook);
        assertEquals(1, chain.size());

        chain.unregister("to-remove");
        assertEquals(0, chain.size());
        assertTrue(chain.isEmpty());
    }

    // ========================================================================
    // 线程安全：AroundHook 的局部变量天然隔离
    // ========================================================================

    @Test
    void testTimingHookThreadSafeByDesign() throws InterruptedException {
        TimingHook timing = new TimingHook();
        AroundHookChain chain = new AroundHookChain();
        chain.register(timing);

        int threads = 20, iters = 100;
        CountDownLatch latch = new CountDownLatch(threads);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try {
                    for (int i = 0; i < iters; i++) {
                        HookEvent event = new HookEvent(HookEventType.PRE_REASONING);
                        HookContext ctx = new HookContext("a", "t", "s", "u", i, null, null);
                        chain.aroundReasoning(event, ctx,
                            e -> {
                                // 模拟工作
                                try { Thread.sleep(0, 100); } catch (InterruptedException ignored) {}
                                return Mono.just(e);
                            }
                        ).block();
                    }
                } catch (Throwable e) {
                    failures.add(e);
                } finally {
                    latch.countDown();
                }
            }, "timing").start();
        }

        latch.await(10, TimeUnit.SECONDS);
        assertTrue(failures.isEmpty(), "不应有异常: " + failures);
        assertEquals(threads * iters, timing.totalCalls.get());

        System.out.println("=== Timing AroundHook 并发 ===");
        System.out.println("调用: " + timing.totalCalls.get() + "  平均耗时: "
            + timing.getAverageElapsedMs() + "ms");
        System.out.println("(start 是局部变量 → 零共享状态 → 编译器保证线程安全)");
    }

    // ========================================================================
    // 混合：链式 Hook + AroundHook 共存
    // ========================================================================

    @Test
    void testChainHookAndAroundHookCoexist() {
        // 模拟 ReActLoop 的完整流程
        HookChain hookChain = new HookChain();
        // 用两个独立的 Hook 来区分 PRE 和 POST
        hookChain.register(new Hook() {
            @Override public String getName() { return "pre-filter"; }
            @Override public java.util.Set<HookEventType> getSubscribedEventTypes() {
                return java.util.Set.of(HookEventType.PRE_REASONING);
            }
            @Override public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
                event.setPayload("chain_pre", "done");
                return Mono.just(HookResult.continue_());
            }
        });
        hookChain.register(new Hook() {
            @Override public String getName() { return "post-filter"; }
            @Override public java.util.Set<HookEventType> getSubscribedEventTypes() {
                return java.util.Set.of(HookEventType.POST_REASONING);
            }
            @Override public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
                event.setPayload("chain_post", "done");
                return Mono.just(HookResult.continue_());
            }
        });
        AroundHookChain aroundChain = new AroundHookChain();
        aroundChain.register(new TimingHook());
        HookPipeline pipeline = new HookPipeline(hookChain, aroundChain);

        HookContext hc = new HookContext("a", "t", "s", "u", 0, null, null);
        HookEvent event = new HookEvent(HookEventType.PRE_REASONING);

        // PRE chain
        HookResult pre = pipeline.dispatch(event, hc).block();
        assertTrue(pre.isContinue());

        // AroundHook
        HookEvent after = aroundChain.aroundReasoning(event, hc,
            e -> { e.setPayload("core_executed", true); return Mono.just(e); }
        ).block();
        assertEquals(Boolean.TRUE, after.<Boolean>getPayload("core_executed"));

        // POST chain（使用 POST_REASONING 类型才能匹配 post-filter hook）
        HookEvent postEvent = new HookEvent(HookEventType.POST_REASONING, after.getPayload());
        HookResult post = pipeline.dispatch(postEvent, hc).block();
        assertTrue(post.isContinue());

        assertEquals("done", after.<String>getPayload("chain_pre"));
        assertEquals("done", postEvent.<String>getPayload("chain_post"));
    }

    // ========================================================================
    // 辅助 Hook 类
    // ========================================================================

    /** 记录执行顺序的 Hook —— 写入 event payload 的共享列表 */
    static class OrderRecordingHook implements AroundHook {
        private final String id;

        OrderRecordingHook(String id) { this.id = id; }

        @Override public String getName() { return id; }

        static void append(HookEvent event, String entry) {
            @SuppressWarnings("unchecked")
            List<String> list = event.getPayload("_order");
            if (list != null) list.add(entry);
        }

        @Override
        public Mono<HookEvent> aroundReasoning(HookEvent event, HookContext ctx,
                                                Function<HookEvent, Mono<HookEvent>> next) {
            append(event, id + "-before");
            return next.apply(event)
                .map(e -> {
                    append(e, id + "-after");
                    return e;
                });
        }
    }

    /** 计时 Hook —— 演示局部变量天然线程安全 */
    static class TimingHook implements AroundHook {
        final AtomicLong totalCalls = new AtomicLong();
        final AtomicLong totalNs = new AtomicLong();

        @Override public String getName() { return "timing"; }

        @Override
        public Mono<HookEvent> aroundReasoning(HookEvent event, HookContext ctx,
                                                Function<HookEvent, Mono<HookEvent>> next) {
            long start = System.nanoTime();
            return next.apply(event)
                .doOnSuccess(e -> {
                    totalCalls.incrementAndGet();
                    totalNs.addAndGet(System.nanoTime() - start);
                });
        }

        double getAverageElapsedMs() {
            long c = totalCalls.get();
            return c > 0 ? (double) totalNs.get() / c / 1_000_000 : 0;
        }
    }

    /** 计数 Hook —— 统计各阶段调用次数 */
    static class AtomicH implements AroundHook {
        final String name;
        final AtomicLong reasoningCount = new AtomicLong();
        final AtomicLong toolCount = new AtomicLong();
        final AtomicLong callCount = new AtomicLong();

        AtomicH(String name) { this.name = name; }

        @Override public String getName() { return name; }

        @Override
        public Mono<HookEvent> aroundReasoning(HookEvent e, HookContext c,
                                                Function<HookEvent, Mono<HookEvent>> n) {
            reasoningCount.incrementAndGet(); return n.apply(e);
        }
        @Override
        public Mono<HookEvent> aroundToolCall(HookEvent e, HookContext c,
                                               Function<HookEvent, Mono<HookEvent>> n) {
            toolCount.incrementAndGet(); return n.apply(e);
        }
        @Override
        public Mono<HookEvent> aroundCall(HookEvent e, HookContext c,
                                           Function<HookEvent, Mono<HookEvent>> n) {
            callCount.incrementAndGet(); return n.apply(e);
        }
    }
}
