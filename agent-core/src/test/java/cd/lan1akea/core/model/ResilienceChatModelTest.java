package cd.lan1akea.core.model;

import cd.lan1akea.core.exception.ModelCallException;
import cd.lan1akea.core.message.Msg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ResilienceChatModel 熔断器测试。
 * 覆盖 CLOSED → OPEN → HALF_OPEN 状态转换全路径。
 */
class ResilienceChatModelTest {

    private StubDelegate delegate;

    @BeforeEach
    void setUp() {
        delegate = new StubDelegate();
    }

    // ═══════════════════════════════════════════════════════════════
    // 正常状态：请求透传
    // ═══════════════════════════════════════════════════════════════

    @Test
    void normalOperationPassesThrough() {
        ResilienceChatModel cb = new ResilienceChatModel(delegate, 3, 10_000);
        delegate.successResponse = true;

        ChatResponse resp = cb.chatWithTools(List.of(), List.of(), null).block();
        assertNotNull(resp);
        assertEquals(ResilienceChatModel.CircuitState.CLOSED, cb.getCircuitState());
        assertEquals(0, cb.getFailureCount());
    }

    @Test
    void delegateMethodsAreForwarded() {
        ResilienceChatModel cb = new ResilienceChatModel(delegate);
        assertEquals("stub-provider", cb.getProvider());
        assertEquals("stub-model", cb.getModelName());
        assertEquals(8192, cb.getMaxInputTokens());
        assertEquals(1024, cb.getDefaultMaxTokens());
        assertEquals(0.7, cb.getDefaultTemperature(), 0.01);
        assertTrue(cb.supportsStreaming());
    }

    // ═══════════════════════════════════════════════════════════════
    // 熔断打开：连续失败达阈值 → OPEN
    // ═══════════════════════════════════════════════════════════════

    @Test
    void consecutiveFailuresOpenCircuit() {
        ResilienceChatModel cb = new ResilienceChatModel(delegate, 3, 10_000);
        delegate.successResponse = false; // 始终失败

        // 前两次：仍然 CLOSED
        for (int i = 0; i < 2; i++) {
            try { cb.chatWithTools(List.of(), List.of(), null).block(); } catch (Exception ignored) {}
        }
        assertEquals(ResilienceChatModel.CircuitState.CLOSED, cb.getCircuitState());
        assertEquals(2, cb.getFailureCount());

        // 第三次：触发 OPEN
        try { cb.chatWithTools(List.of(), List.of(), null).block(); } catch (Exception ignored) {}
        assertEquals(ResilienceChatModel.CircuitState.OPEN, cb.getCircuitState());
        assertEquals(3, cb.getFailureCount());
    }

    @Test
    void openCircuitRejectsRequestsImmediately() {
        ResilienceChatModel cb = new ResilienceChatModel(delegate, 1, 30_000);
        delegate.successResponse = false;

        // 一次失败即打开
        try { cb.chatWithTools(List.of(), List.of(), null).block(); } catch (Exception ignored) {}
        assertEquals(ResilienceChatModel.CircuitState.OPEN, cb.getCircuitState());

        // 后续请求直接拒绝
        try {
            cb.chatWithTools(List.of(), List.of(), null).block();
            fail("Expected ModelCallException");
        } catch (ModelCallException e) {
            assertTrue(e.getMessage().contains("熔断器已打开"));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 半开恢复：等待后尝试
    // ═══════════════════════════════════════════════════════════════

    @Test
    void halfOpenAfterTimeout() {
        ResilienceChatModel cb = new ResilienceChatModel(delegate, 1, 100);
        delegate.successResponse = false;

        // 触发 OPEN
        try { cb.chatWithTools(List.of(), List.of(), null).block(); } catch (Exception ignored) {}
        assertEquals(ResilienceChatModel.CircuitState.OPEN, cb.getCircuitState());

        // 等待超过 halfOpenAfterMs
        try { Thread.sleep(150); } catch (InterruptedException ignored) {}

        // 状态应转为 HALF_OPEN
        assertEquals(ResilienceChatModel.CircuitState.HALF_OPEN, cb.getCircuitState());
    }

    @Test
    void halfOpenSuccessClosesCircuit() {
        ResilienceChatModel cb = new ResilienceChatModel(delegate, 1, 50);
        delegate.successResponse = false;

        // 触发 OPEN
        try { cb.chatWithTools(List.of(), List.of(), null).block(); } catch (Exception ignored) {}
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        // HALF_OPEN + 成功 → CLOSED
        delegate.successResponse = true;
        ChatResponse resp = cb.chatWithTools(List.of(), List.of(), null).block();
        assertNotNull(resp);
        assertEquals(ResilienceChatModel.CircuitState.CLOSED, cb.getCircuitState());
        assertEquals(0, cb.getFailureCount());
    }

    @Test
    void halfOpenFailureReopensCircuit() {
        ResilienceChatModel cb = new ResilienceChatModel(delegate, 2, 50);
        delegate.successResponse = false;

        // 先触发 OPEN（failureThreshold=2，设 failCount 到 2）
        try { cb.chatWithTools(List.of(), List.of(), null).block(); } catch (Exception ignored) {}
        try { cb.chatWithTools(List.of(), List.of(), null).block(); } catch (Exception ignored) {}

        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        // HALF_OPEN 试探失败 → 重新 OPEN
        delegate.successResponse = false;
        try { cb.chatWithTools(List.of(), List.of(), null).block(); } catch (Exception ignored) {}
        assertEquals(ResilienceChatModel.CircuitState.OPEN, cb.getCircuitState());
    }

    // ═══════════════════════════════════════════════════════════════
    // 手动重置
    // ═══════════════════════════════════════════════════════════════

    @Test
    void manualResetClearsState() {
        ResilienceChatModel cb = new ResilienceChatModel(delegate, 1, 30_000);
        delegate.successResponse = false;

        try { cb.chatWithTools(List.of(), List.of(), null).block(); } catch (Exception ignored) {}
        assertEquals(ResilienceChatModel.CircuitState.OPEN, cb.getCircuitState());

        cb.reset();
        assertEquals(ResilienceChatModel.CircuitState.CLOSED, cb.getCircuitState());
        assertEquals(0, cb.getFailureCount());
    }

    // ═══════════════════════════════════════════════════════════════
    // 成功重置计数器
    // ═══════════════════════════════════════════════════════════════

    @Test
    void successResetsFailureCount() {
        ResilienceChatModel cb = new ResilienceChatModel(delegate, 5, 10_000);
        delegate.successResponse = false;

        // 2 次失败
        try { cb.chatWithTools(List.of(), List.of(), null).block(); } catch (Exception ignored) {}
        try { cb.chatWithTools(List.of(), List.of(), null).block(); } catch (Exception ignored) {}
        assertEquals(2, cb.getFailureCount());

        // 一次成功 → 清零
        delegate.successResponse = true;
        cb.chatWithTools(List.of(), List.of(), null).block();
        assertEquals(0, cb.getFailureCount());
    }

    // ═══════════════════════════════════════════════════════════════
    // stream/chat 直接委托不走熔断
    // ═══════════════════════════════════════════════════════════════

    @Test
    void streamMethodsDelegateDirectly() {
        ResilienceChatModel cb = new ResilienceChatModel(delegate);
        List<ChatStreamChunk> chunks = cb.stream(List.of(), null).collectList().block();
        assertNotNull(chunks);
        assertEquals(1, chunks.size());

        ChatResponse resp = cb.chat(List.of(), null).block();
        assertNotNull(resp);
    }

    // ═══════════════════════════════════════════════════════════════
    // 多线程并发
    // ═══════════════════════════════════════════════════════════════

    @Test
    void concurrentFailuresOpenCircuitAndRejectSubsequent() throws Exception {
        ResilienceChatModel cb = new ResilienceChatModel(delegate, 3, 30_000);
        delegate.successResponse = false;

        AtomicInteger rejectedCount = new AtomicInteger(0);
        int totalCalls = 20;
        Thread[] ts = new Thread[totalCalls];
        for (int i = 0; i < totalCalls; i++) {
            ts[i] = new Thread(() -> {
                try {
                    cb.chatWithTools(List.of(), List.of(), null).block();
                } catch (Exception e) {
                    if (e.getMessage() != null && e.getMessage().contains("熔断器已打开")) {
                        rejectedCount.incrementAndGet();
                    }
                }
            });
        }
        for (Thread t : ts) t.start();
        for (Thread t : ts) t.join();

        // 并发下至少有一些请求被熔断拒绝
        assertTrue(rejectedCount.get() > 0, "并发失败应触发熔断并拒绝后续请求");
        assertEquals(ResilienceChatModel.CircuitState.OPEN, cb.getCircuitState());
    }

    // ═══════════════════════════════════════════════════════════════
    // Stub delegate
    // ═══════════════════════════════════════════════════════════════

    static class StubDelegate implements ChatModel {
        boolean successResponse = true;

        @Override public String getProvider() { return "stub-provider"; }
        @Override public String getModelName() { return "stub-model"; }
        @Override public int getMaxInputTokens() { return 8192; }
        @Override public int getDefaultMaxTokens() { return 1024; }
        @Override public double getDefaultTemperature() { return 0.7; }
        @Override public boolean supportsStreaming() { return true; }

        @Override
        public Mono<ChatResponse> chat(List<Msg> messages, GenerateOptions options) {
            return Mono.just(new ChatResponse(cd.lan1akea.core.message.AssistantMessage.of("ok"),
                new ChatUsage(0, 0), "stop", "stub"));
        }

        @Override
        public Flux<ChatStreamChunk> stream(List<Msg> messages, GenerateOptions options) {
            return Flux.just(ChatStreamChunk.builder().delta("ok").type(ChatStreamChunk.TYPE_TEXT)
                .finishReason("stop").build());
        }

        @Override
        public Flux<ChatStreamChunk> streamWithTools(List<Msg> msgs, List<ToolSchema> schemas, GenerateOptions opts) {
            return Flux.just(ChatStreamChunk.builder().delta("ok").type(ChatStreamChunk.TYPE_TEXT)
                .finishReason("stop").build());
        }

        @Override
        public Mono<ChatResponse> chatWithTools(List<Msg> msgs, List<ToolSchema> schemas, GenerateOptions opts) {
            if (successResponse) {
                return Mono.just(new ChatResponse(cd.lan1akea.core.message.AssistantMessage.of("ok"),
                    new ChatUsage(0, 0), "stop", "stub"));
            }
            return Mono.error(new RuntimeException("模拟 LLM 调用失败"));
        }
    }
}
