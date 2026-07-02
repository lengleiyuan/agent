package cd.lan1akea.harness.hook;

import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.UserMessage;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.tool.ToolBase;
import cd.lan1akea.core.tool.ToolCallContext;
import cd.lan1akea.core.tool.ToolResult;
import cd.lan1akea.harness.HarnessAgent;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class IAroundHookTest {

    @Test
    void aroundHookRegisteredViaBuilder() {
        TimingHook hook = new TimingHook();
        HarnessAgent agent = HarnessAgent.builder()
            .name("around-test")
            .model(new EchoModel())
            .aroundHook(hook)
            .build();

        assertEquals("timing", hook.getName());
        assertNotNull(agent);
    }

    @Test
    void fullChainAroundHookWrapsReasoning() {
        TimingHook timingHook = new TimingHook();
        EchoModel model = new EchoModel();

        HarnessAgent agent = HarnessAgent.builder()
            .name("around-chain-test")
            .model(model)
            .aroundHook(timingHook)
            .tool(new CalculatorTool())
            .maxIterations(5)
            .build();

        ChatResponse resp = agent.chat(List.of(UserMessage.of("1+1=?"))).block();

        assertNotNull(resp);
        assertTrue(timingHook.getTotalCalls() >= 1,
            "AroundHook 应在推理阶段被调用，实际调用=" + timingHook.getTotalCalls());
        assertTrue(timingHook.getAverageElapsedMs() >= 0);
        System.out.println("AroundHook 推理次数: " + timingHook.getTotalCalls()
            + ", 平均耗时: " + timingHook.getAverageElapsedMs() + "ms");
    }

    @Test
    void fullChainMultipleAroundHooksAreOnionLayered() {
        OrderRecorderHook outer = new OrderRecorderHook("outer");
        OrderRecorderHook inner = new OrderRecorderHook("inner");
        EchoModel model = new EchoModel();

        HarnessAgent agent = HarnessAgent.builder()
            .name("onion-test")
            .model(model)
            .aroundHook(outer)
            .aroundHook(inner)
            .maxIterations(3)
            .build();

        agent.chat(List.of(UserMessage.of("hello"))).block();

        List<String> order = OrderRecorderHook.getOrder();
        assertTrue(order.size() >= 4, "至少应有 4 次记录: " + order);
        int outerBefore = order.indexOf("outer-before");
        int innerBefore = order.indexOf("inner-before");
        assertTrue(outerBefore < innerBefore,
            "outer 先注册应在最外层(before 先执行): " + order);
        int innerAfter = order.indexOf("inner-after");
        int outerAfter = order.indexOf("outer-after");
        assertTrue(innerAfter < outerAfter,
            "内层 after 先执行: " + order);
    }

    @Test
    void aroundHookReceivesContext() {
        AtomicLong seenIteration = new AtomicLong(-1);
        EchoModel model = new EchoModel();

        HarnessAgent agent = HarnessAgent.builder()
            .name("ctx-test")
            .model(model)
            .aroundHook(new AroundHook() {
                @Override public String getName() { return "ctx-checker"; }
                @Override
                public Mono<HookEvent> aroundReasoning(HookEvent event, HookContext ctx,
                                                        Function<HookEvent, Mono<HookEvent>> next) {
                    seenIteration.set(ctx.getCurrentIteration());
                    return next.apply(event);
                }
            })
            .maxIterations(3)
            .build();

        agent.chat(List.of(UserMessage.of("test"))).block();
        assertTrue(seenIteration.get() >= 0, "AroundHook 应收到 HookContext");
    }

    @Test
    void aroundHookAndChainHookCoexist() {
        TimingHook around = new TimingHook();
        AtomicLong chainCalled = new AtomicLong(0);

        EchoModel model = new EchoModel();
        HarnessAgent agent = HarnessAgent.builder()
            .name("coexist-test")
            .model(model)
            .aroundHook(around)
            .hook(new Hook() {
                @Override public String getName() { return "chain-counter"; }
                @Override public Set<HookEventType> getSubscribedEventTypes() {
                    return Set.of(HookEventType.PRE_REASONING);
                }
                @Override public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
                    chainCalled.incrementAndGet();
                    return Mono.just(HookResult.continue_());
                }
            })
            .maxIterations(3)
            .build();

        agent.chat(List.of(UserMessage.of("coexist test"))).block();

        assertTrue(around.getTotalCalls() >= 1, "AroundHook 应执行");
        assertTrue(chainCalled.get() >= 1, "链式 Hook 也应执行");
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    static class TimingHook implements AroundHook {
        private final AtomicLong totalCalls = new AtomicLong();
        private final AtomicLong totalNs = new AtomicLong();

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

        long getTotalCalls() { return totalCalls.get(); }
        double getAverageElapsedMs() {
            long c = totalCalls.get();
            return c > 0 ? (double) totalNs.get() / c / 1_000_000 : 0;
        }
    }

    static class OrderRecorderHook implements AroundHook {
        private static final List<String> ORDER = Collections.synchronizedList(new ArrayList<>());
        private final String id;

        OrderRecorderHook(String id) { this.id = id; }
        @Override public String getName() { return id; }

        @Override
        public Mono<HookEvent> aroundReasoning(HookEvent event, HookContext ctx,
                                                Function<HookEvent, Mono<HookEvent>> next) {
            ORDER.add(id + "-before");
            return next.apply(event)
                .doOnSuccess(e -> ORDER.add(id + "-after"));
        }

        static List<String> getOrder() { return new ArrayList<>(ORDER); }
    }

    static class EchoModel extends ChatModelBase {
        EchoModel() { super("dev", "echo", new cd.lan1akea.core.formatter.OpenAiMessageFormatter()); }

        @Override protected Map<String, String> buildAuthHeaders() { return Map.of(); }
        @Override protected String buildApiUrl() { return "http://localhost/echo"; }

        @Override
        protected Mono<ChatResponse> doChat(List<Map<String, Object>> messages,
                                             List<ToolSchema> toolSchemas,
                                             GenerateOptions options) {
            Msg msg = cd.lan1akea.core.message.AssistantMessage.of("[回显] 收到 " + messages.size() + " 条消息");
            return Mono.just(new ChatResponse(msg, new ChatUsage(1, 1), "stop", "echo"));
        }

        @Override
        protected reactor.core.publisher.Flux<ChatStreamChunk> doStream(
                List<Map<String, Object>> messages, List<ToolSchema> toolSchemas,
                GenerateOptions options) {
            return reactor.core.publisher.Flux.just(
                ChatStreamChunk.builder().delta("[stream]").type(ChatStreamChunk.TYPE_TEXT).build());
        }
    }

    static class CalculatorTool extends ToolBase {
        CalculatorTool() { declareStringParam("expression", "数学表达式", true); }
        @Override public String getName() { return "calculator"; }
        @Override public String getDescription() { return "计算器"; }
        @Override
        public Mono<ToolResult> execute(ToolCallContext ctx) {
            return Mono.just(ToolResult.success("42"));
        }
    }
}
