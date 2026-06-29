package cd.lan1akea.core.hook;

import cd.lan1akea.core.model.ChatStreamChunk;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class AroundHookStreamTest {

    // ========================================================================
    // aroundReasoningStream
    // ========================================================================

    @Test
    void testAroundReasoningStreamDefaultPassthrough() {
        AroundHook hook = new AroundHook() {
            @Override public String getName() { return "test"; }
        };
        HookContext ctx = new HookContext("a", "t", "s", "u", 0, null, null);
        HookEvent event = new HookEvent(HookEventType.PRE_REASONING);

        Flux<ChatStreamChunk> result = hook.aroundReasoningStream(event, ctx,
            e -> Flux.just(chunk("hello"), chunk("world")));

        StepVerifier.create(result)
            .assertNext(c -> assertEquals("hello", c.getDelta()))
            .assertNext(c -> assertEquals("world", c.getDelta()))
            .verifyComplete();
    }

    @Test
    void testAroundReasoningStreamWrapsCorrectly() {
        List<String> log = new ArrayList<>();
        AroundHook hook = new AroundHook() {
            @Override public String getName() { return "log"; }
            @Override
            public Flux<ChatStreamChunk> aroundReasoningStream(HookEvent e, HookContext c,
                    Function<HookEvent, Flux<ChatStreamChunk>> next) {
                log.add("before");
                return next.apply(e).doOnTerminate(() -> log.add("after"));
            }
        };
        HookContext ctx = new HookContext("a", "t", "s", "u", 0, null, null);
        HookEvent event = new HookEvent(HookEventType.PRE_REASONING);

        StepVerifier.create(hook.aroundReasoningStream(event, ctx,
            e -> Flux.just(chunk("data"))))
            .assertNext(c -> assertEquals("data", c.getDelta()))
            .verifyComplete();

        assertEquals("before", log.get(0));
        assertEquals("after", log.get(1));
    }

    @Test
    void testAroundReasoningStreamChainOnionOrder() {
        List<String> log = new ArrayList<>();
        AroundHook outer = new AroundHook() {
            @Override public String getName() { return "outer"; }
            @Override
            public Flux<ChatStreamChunk> aroundReasoningStream(HookEvent e, HookContext c,
                    Function<HookEvent, Flux<ChatStreamChunk>> next) {
                log.add("outer-before");
                return next.apply(e).doOnTerminate(() -> log.add("outer-after"));
            }
        };
        AroundHook inner = new AroundHook() {
            @Override public String getName() { return "inner"; }
            @Override
            public Flux<ChatStreamChunk> aroundReasoningStream(HookEvent e, HookContext c,
                    Function<HookEvent, Flux<ChatStreamChunk>> next) {
                log.add("inner-before");
                return next.apply(e).doOnTerminate(() -> log.add("inner-after"));
            }
        };

        AroundHookChain chain = new AroundHookChain();
        chain.register(outer);  // 先注册 = 外层
        chain.register(inner);  // 后注册 = 内层

        HookContext ctx = new HookContext("a", "t", "s", "u", 0, null, null);
        HookEvent event = new HookEvent(HookEventType.PRE_REASONING);

        StepVerifier.create(chain.aroundReasoningStream(event, ctx,
            e -> Flux.just(chunk("core"))))
            .expectNextMatches(c -> "core".equals(c.getDelta()))
            .verifyComplete();

        // 外层先执行 before，内层后执行 before；after 反向
        assertEquals("outer-before", log.get(0));
        assertEquals("inner-before", log.get(1));
        assertEquals("inner-after", log.get(2));
        assertEquals("outer-after", log.get(3));
    }

    @Test
    void testAroundReasoningStreamEmptyChainPassthrough() {
        AroundHookChain chain = new AroundHookChain();
        HookContext ctx = new HookContext("a", "t", "s", "u", 0, null, null);
        HookEvent event = new HookEvent(HookEventType.PRE_REASONING);

        StepVerifier.create(chain.aroundReasoningStream(event, ctx,
            e -> Flux.just(chunk("passthrough"))))
            .assertNext(c -> assertEquals("passthrough", c.getDelta()))
            .verifyComplete();
    }

    // ========================================================================
    // aroundCallStream
    // ========================================================================

    @Test
    void testAroundCallStreamDefaultPassthrough() {
        AroundHook hook = new AroundHook() {
            @Override public String getName() { return "test"; }
        };
        HookContext ctx = new HookContext("a", "t", "s", "u", 0, null, null);
        HookEvent event = new HookEvent(null);

        Flux<ChatStreamChunk> result = hook.aroundCallStream(event, ctx,
            e -> Flux.just(chunk("a"), chunk("b")));

        StepVerifier.create(result)
            .expectNextCount(2)
            .verifyComplete();
    }

    @Test
    void testAroundCallStreamWrapsCorrectly() {
        AtomicInteger beforeCount = new AtomicInteger(0);
        AtomicInteger afterCount = new AtomicInteger(0);
        AroundHook hook = new AroundHook() {
            @Override public String getName() { return "timer"; }
            @Override
            public Flux<ChatStreamChunk> aroundCallStream(HookEvent e, HookContext c,
                    Function<HookEvent, Flux<ChatStreamChunk>> core) {
                beforeCount.incrementAndGet();
                return core.apply(e).doOnTerminate(afterCount::incrementAndGet);
            }
        };

        HookContext ctx = new HookContext("a", "t", "s", "u", 0, null, null);
        HookEvent event = new HookEvent(null);

        StepVerifier.create(hook.aroundCallStream(event, ctx,
            e -> Flux.just(chunk("x"))))
            .expectNextCount(1)
            .verifyComplete();

        assertEquals(1, beforeCount.get());
        assertEquals(1, afterCount.get());
    }

    @Test
    void testAroundCallStreamChain() {
        List<String> log = new ArrayList<>();
        AroundHook a = new AroundHook() {
            @Override public String getName() { return "A"; }
            @Override
            public Flux<ChatStreamChunk> aroundCallStream(HookEvent e, HookContext c,
                    Function<HookEvent, Flux<ChatStreamChunk>> core) {
                log.add("A-before");
                return core.apply(e).doOnTerminate(() -> log.add("A-after"));
            }
        };
        AroundHook b = new AroundHook() {
            @Override public String getName() { return "B"; }
            @Override
            public Flux<ChatStreamChunk> aroundCallStream(HookEvent e, HookContext c,
                    Function<HookEvent, Flux<ChatStreamChunk>> core) {
                log.add("B-before");
                return core.apply(e).doOnTerminate(() -> log.add("B-after"));
            }
        };

        AroundHookChain chain = new AroundHookChain();
        chain.register(a);
        chain.register(b);

        HookContext ctx = new HookContext("a", "t", "s", "u", 0, null, null);
        HookEvent event = new HookEvent(null);

        StepVerifier.create(chain.aroundCallStream(event, ctx,
            e -> Flux.just(chunk("x"))))
            .expectNextCount(1)
            .verifyComplete();

        assertEquals("A-before", log.get(0));
        assertEquals("B-before", log.get(1));
        assertEquals("B-after", log.get(2));
        assertEquals("A-after", log.get(3));
    }

    @Test
    void testAroundCallStreamChainModifiesChunks() {
        AroundHook prefix = new AroundHook() {
            @Override public String getName() { return "prefix"; }
            @Override
            public Flux<ChatStreamChunk> aroundCallStream(HookEvent e, HookContext c,
                    Function<HookEvent, Flux<ChatStreamChunk>> core) {
                return Flux.just(chunk("[START]"))
                    .concatWith(core.apply(e))
                    .concatWith(Flux.just(chunk("[END]")));
            }
        };

        AroundHookChain chain = new AroundHookChain();
        chain.register(prefix);
        HookContext ctx = new HookContext("a", "t", "s", "u", 0, null, null);
        HookEvent event = new HookEvent(null);

        StepVerifier.create(chain.aroundCallStream(event, ctx,
            e -> Flux.just(chunk("core"))))
            .assertNext(c -> assertEquals("[START]", c.getDelta()))
            .assertNext(c -> assertEquals("core", c.getDelta()))
            .assertNext(c -> assertEquals("[END]", c.getDelta()))
            .verifyComplete();
    }

    // ========================================================================
    // Integration: stream hooks coexist with non-stream hooks
    // ========================================================================

    @Test
    void testStreamAndNonStreamHooksInSameChain() {
        List<String> log = new ArrayList<>();
        AroundHook dual = new AroundHook() {
            @Override public String getName() { return "dual"; }
            @Override
            public Mono<HookEvent> aroundReasoning(HookEvent e, HookContext c,
                    Function<HookEvent, Mono<HookEvent>> next) {
                log.add("mono");
                return next.apply(e);
            }
            @Override
            public Flux<ChatStreamChunk> aroundReasoningStream(HookEvent e, HookContext c,
                    Function<HookEvent, Flux<ChatStreamChunk>> next) {
                log.add("flux");
                return next.apply(e);
            }
            @Override
            public Mono<HookEvent> aroundCall(HookEvent e, HookContext c,
                    Function<HookEvent, Mono<HookEvent>> next) {
                log.add("call-mono");
                return next.apply(e);
            }
            @Override
            public Flux<ChatStreamChunk> aroundCallStream(HookEvent e, HookContext c,
                    Function<HookEvent, Flux<ChatStreamChunk>> core) {
                log.add("call-flux");
                return core.apply(e);
            }
        };

        AroundHookChain chain = new AroundHookChain();
        chain.register(dual);
        assertFalse(chain.isEmpty());

        HookContext ctx = new HookContext("a", "t", "s", "u", 0, null, null);

        // 流式路径
        StepVerifier.create(chain.aroundReasoningStream(new HookEvent(HookEventType.PRE_REASONING), ctx,
            e -> Flux.just(chunk("ok"))))
            .expectNextCount(1).verifyComplete();
        assertEquals("flux", log.get(0));
        log.clear();

        // 非流式路径 仍然正常工作
        StepVerifier.create(chain.aroundReasoning(new HookEvent(HookEventType.PRE_REASONING), ctx, Mono::just))
            .expectNextCount(1).verifyComplete();
        assertEquals("mono", log.get(0));
        log.clear();

        // aroundCall 流式
        StepVerifier.create(chain.aroundCallStream(new HookEvent(null), ctx,
            e -> Flux.just(chunk("ok"))))
            .expectNextCount(1).verifyComplete();
        assertEquals("call-flux", log.get(0));
        log.clear();

        // aroundCall 非流式
        StepVerifier.create(chain.aroundCall(new HookEvent(null), ctx, Mono::just))
            .expectNextCount(1).verifyComplete();
        assertEquals("call-mono", log.get(0));
    }

    // ========================================================================
    // Error propagation
    // ========================================================================

    @Test
    void testAroundReasoningStreamErrorPropagatesThroughChain() {
        AroundHook hook = new AroundHook() {
            @Override public String getName() { return "err"; }
            @Override
            public Flux<ChatStreamChunk> aroundReasoningStream(HookEvent e, HookContext c,
                    Function<HookEvent, Flux<ChatStreamChunk>> next) {
                return next.apply(e);
            }
        };
        HookContext ctx = new HookContext("a", "t", "s", "u", 0, null, null);
        HookEvent event = new HookEvent(HookEventType.PRE_REASONING);

        StepVerifier.create(hook.aroundReasoningStream(event, ctx,
            e -> Flux.error(new RuntimeException("model error"))))
            .expectError(RuntimeException.class)
            .verify(Duration.ofSeconds(1));
    }

    // ========================================================================
    // Modifies chunk content through hook
    // ========================================================================

    @Test
    void testAroundReasoningStreamModifiesChunks() {
        AroundHook uppercase = new AroundHook() {
            @Override public String getName() { return "upper"; }
            @Override
            public Flux<ChatStreamChunk> aroundReasoningStream(HookEvent e, HookContext c,
                    Function<HookEvent, Flux<ChatStreamChunk>> next) {
                return next.apply(e).map(chunk ->
                    ChatStreamChunk.builder()
                        .delta(chunk.getDelta().toUpperCase())
                        .type(chunk.getType())
                        .build());
            }
        };
        HookContext ctx = new HookContext("a", "t", "s", "u", 0, null, null);
        HookEvent event = new HookEvent(HookEventType.PRE_REASONING);

        StepVerifier.create(uppercase.aroundReasoningStream(event, ctx,
            e -> Flux.just(chunk("hello"), chunk("world"))))
            .assertNext(c -> assertEquals("HELLO", c.getDelta()))
            .assertNext(c -> assertEquals("WORLD", c.getDelta()))
            .verifyComplete();
    }

    // ========================================================================
    // Helper
    // ========================================================================

    private static ChatStreamChunk chunk(String text) {
        return ChatStreamChunk.builder().delta(text).type(ChatStreamChunk.TYPE_TEXT).build();
    }
}
