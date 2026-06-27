package cd.lan1akea.harness.hook;

import cd.lan1akea.core.hook.HookContext;
import cd.lan1akea.core.hook.HookEvent;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.UserMessage;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.tool.ToolResult;
import cd.lan1akea.harness.HarnessAgent;
import cd.lan1akea.harness.tool.IBaseTool;
import cd.lan1akea.harness.context.ToolContext;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class IAroundHookTest {

    // ========================================================================
    // 1. HarnessAroundHook 接口实现
    // ========================================================================

    @Test
    void harnessAroundHookIsAroundHook() {
        IAroundHook hook = new TimingHook();
        assertTrue(hook instanceof cd.lan1akea.core.hook.AroundHook,
            "HarnessAroundHook 应是 core AroundHook 子类型");
    }

    @Test
    void harnessAroundHookRegisteredViaBuilder() {
        TimingHook hook = new TimingHook();

        HarnessAgent agent = HarnessAgent.builder()
            .name("around-test")
            .model(new EchoModel())
            .aroundHook(hook)
            .build();

        assertEquals("timing", hook.getName());
        assertNotNull(agent);
    }

    // ========================================================================
    // 2. 完整链路：Builder → AroundHook → ReActLoop
    // ========================================================================

    @Test
    void fullChainAroundHookWrapsReasoning() {
        TimingHook timingHook = new TimingHook();
        EchoModel model = new EchoModel();

        HarnessAgent agent = HarnessAgent.builder()
            .name("around-chain-test")
            .model(model)
            .aroundHook(timingHook)
            .tool(new CalculatorITool())
            .maxIterations(5)
            .build();

        // 发送消息 —— 走完整推理链路
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

        // 验证洋葱顺序：outer-before → inner-before → core → inner-after → outer-after
        List<String> order = OrderRecorderHook.getOrder();
        assertTrue(order.size() >= 4, "至少应有 4 次记录: " + order);
        // 检查 before 顺序
        int outerBefore = order.indexOf("outer-before");
        int innerBefore = order.indexOf("inner-before");
        assertTrue(outerBefore < innerBefore,
            "outer 先注册应在最外层(before 先执行): " + order);
        // 检查 after 顺序（反向）
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
            .aroundHook(new IAroundHook() {
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

    // ========================================================================
    // 3. AroundHook + 链式 Hook 共存
    // ========================================================================

    @Test
    void aroundHookAndChainHookCoexist() {
        TimingHook around = new TimingHook();
        AtomicLong chainCalled = new AtomicLong(0);

        EchoModel model = new EchoModel();
        HarnessAgent agent = HarnessAgent.builder()
            .name("coexist-test")
            .model(model)
            .aroundHook(around)
            .hook(new cd.lan1akea.core.hook.Hook() {
                @Override public String getName() { return "chain-counter"; }
                @Override public Set<cd.lan1akea.core.hook.HookEventType> getSubscribedEventTypes() {
                    return Set.of(cd.lan1akea.core.hook.HookEventType.PRE_REASONING);
                }
                @Override public Mono<cd.lan1akea.core.hook.HookResult> onEvent(
                        cd.lan1akea.core.hook.HookEvent event,
                        cd.lan1akea.core.hook.HookContext context) {
                    chainCalled.incrementAndGet();
                    return Mono.just(cd.lan1akea.core.hook.HookResult.continue_());
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

    static class TimingHook implements IAroundHook {
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

    static class OrderRecorderHook implements IAroundHook {
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

    /** 回显模型：永远返回纯文本（不调用工具），避免外部 API 依赖 */
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

    static class CalculatorITool extends IBaseTool {
        @Override public String getName() { return "calculator"; }
        @Override public String getDescription() { return "计算器"; }
        @Override public ToolSchema getParameters() {
            Map<String, Object> props = Map.of("expression", Map.of("type", "string", "description", "表达式"));
            return new ToolSchema("calculator", "计算器", Map.of("type", "object", "properties", props));
        }
        @Override
        protected ToolResult doExecute(ToolContext ctx) {
            return ToolResult.success("42");
        }
    }
}
