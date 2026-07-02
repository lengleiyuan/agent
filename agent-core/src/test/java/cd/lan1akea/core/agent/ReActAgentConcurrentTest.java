package cd.lan1akea.core.agent;

import cd.lan1akea.core.agent.config.AgentConfig;
import cd.lan1akea.core.agent.config.AgentExecutionConfig;
import cd.lan1akea.core.agent.loop.LoopContext;
import cd.lan1akea.core.context.RuntimeContext;
import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.message.*;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.tool.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReActAgent 并发会话支持测试。
 *
 * 验证 P2 改动：AtomicReference → ConcurrentHashMap 支持并发请求。
 */
class ReActAgentConcurrentTest {

    private StubModel model;
    private ToolRegistry toolRegistry;
    private ReActAgent agent;

    @BeforeEach
    void setUp() {
        model = new StubModel();
        toolRegistry = new ToolRegistry();
        AgentConfig config = AgentConfig.builder()
            .name("TestAgent")
            .model(model)
            .toolRegistry(toolRegistry)
            .executionConfig(AgentExecutionConfig.builder().maxIterations(1).build())
            .build();
        agent = new ReActAgent(config);
        agent.build().block();
    }

    // ═══════════════════════════════════════════════════════════════
    // 并发请求跟踪
    // ═══════════════════════════════════════════════════════════════

    @Test
    void multipleConcurrentRequestsAreTracked() throws Exception {
        int threads = 4;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            new Thread(() -> {
                try {
                    startLatch.await();
                    RuntimeContext ctx = RuntimeContext.builder()
                        .tenantId("t1").sessionId("s" + idx).build();
                    agent.chat(List.of(UserMessage.of("msg-" + idx)), ctx).block();
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        doneLatch.await(5, TimeUnit.SECONDS);

        // 请求完成后 map 为空
        assertFalse(agent.isRunning());
    }

    @Test
    void activeRequestsTrackedViaIsRunning() {
        agent.chat(List.of(UserMessage.of("hello")),
            RuntimeContext.builder().sessionId("s1").build()).block();
        assertFalse(agent.isRunning(), "Should be idle after completion");
    }

    // ═══════════════════════════════════════════════════════════════
    // interrupt() 中断所有活跃请求
    // ═══════════════════════════════════════════════════════════════

    @Test
    void interruptAffectsAllActiveRequests() throws Exception {
        // 用延迟模型让请求一直等待
        CountDownLatch blockLatch = new CountDownLatch(1);
        model.setBlockLatch(blockLatch);

        CountDownLatch started = new CountDownLatch(2);
        CopyOnWriteArrayList<Boolean> interrupted = new CopyOnWriteArrayList<>();

        Runnable task = () -> {
            started.countDown();
            try {
                agent.chat(List.of(UserMessage.of("hello")),
                    RuntimeContext.builder().sessionId(UUID.randomUUID().toString()).build()).block();
                interrupted.add(false);
            } catch (Exception e) {
                interrupted.add(true);
            }
        };

        new Thread(task).start();
        new Thread(task).start();
        started.await(2, TimeUnit.SECONDS);

        agent.interrupt();
        blockLatch.countDown(); // 释放所有阻塞的请求

        Thread.sleep(200);
        assertFalse(agent.isRunning(), "Should be idle after interrupt + unblock");
    }

    // ═══════════════════════════════════════════════════════════════
    // interruptBySession 精确中断
    // ═══════════════════════════════════════════════════════════════

    @Test
    void interruptBySessionTargetsSpecificSession() throws Exception {
        CountDownLatch blockLatch = new CountDownLatch(1);
        model.setBlockLatch(blockLatch);

        CountDownLatch started = new CountDownLatch(2);

        Thread t1 = new Thread(() -> {
            started.countDown();
            try {
                agent.chat(List.of(UserMessage.of("hello")),
                    RuntimeContext.builder().sessionId("session-A").build()).block();
            } catch (Exception ignored) {}
        });

        Thread t2 = new Thread(() -> {
            started.countDown();
            try {
                agent.chat(List.of(UserMessage.of("hello")),
                    RuntimeContext.builder().sessionId("session-B").build()).block();
            } catch (Exception ignored) {}
        });

        t1.start(); t2.start();
        started.await(2, TimeUnit.SECONDS);
        Thread.sleep(100); // 等待请求进入活跃状态

        // 只中断 session-A
        agent.interruptBySession("session-A");

        // interrupt 设置标志后，请求可能还在等待 model，所以用 blockLatch 释放
        blockLatch.countDown();

        t1.join(3000);
        t2.join(3000);
        assertFalse(agent.isRunning());
    }

    // ═══════════════════════════════════════════════════════════════
    // interruptBySession(null) 不中断
    // ═══════════════════════════════════════════════════════════════

    @Test
    void interruptBySessionNullDoesNotAffect() {
        CountDownLatch blockLatch = new CountDownLatch(1);
        model.setBlockLatch(blockLatch);

        new Thread(() -> {
            try {
                agent.chat(List.of(UserMessage.of("hello")),
                    RuntimeContext.builder().sessionId("s1").build()).block();
            } catch (Exception ignored) {}
        }).start();

        try { Thread.sleep(100); } catch (Exception ignored) {}

        agent.interruptBySession(null); // 不应中断任何请求
        assertTrue(agent.isRunning());

        blockLatch.countDown();
    }

    // ═══════════════════════════════════════════════════════════════
    // 请求完成 → activeRequests 清理
    // ═══════════════════════════════════════════════════════════════

    @Test
    void requestCompletionRemovesFromActiveMap() {
        agent.chat(List.of(UserMessage.of("hello")),
            RuntimeContext.builder().tenantId("t1").sessionId("s1").build()).block();
        assertFalse(agent.isRunning());
    }

    // ═══════════════════════════════════════════════════════════════
    // shutdown() 清理所有活跃请求
    // ═══════════════════════════════════════════════════════════════

    @Test
    void shutdownClearsAllActiveRequests() {
        CountDownLatch blockLatch = new CountDownLatch(1);
        model.setBlockLatch(blockLatch);

        new Thread(() -> {
            try {
                agent.chat(List.of(UserMessage.of("hello")),
                    RuntimeContext.builder().sessionId("s1").build()).block();
            } catch (Exception ignored) {}
        }).start();

        try { Thread.sleep(100); } catch (Exception ignored) {}

        agent.shutdown().block();
        assertFalse(agent.isRunning());

        blockLatch.countDown();
    }

    // ═══════════════════════════════════════════════════════════════
    // interrupt(Msg) 附带反馈中断所有活跃
    // ═══════════════════════════════════════════════════════════════

    @Test
    void interruptWithMsgAffectsAllActive() throws Exception {
        CountDownLatch blockLatch = new CountDownLatch(1);
        model.setBlockLatch(blockLatch);

        CountDownLatch started = new CountDownLatch(2);
        new Thread(() -> {
            started.countDown();
            try {
                agent.chat(List.of(UserMessage.of("hello")),
                    RuntimeContext.builder().sessionId("a").build()).block();
            } catch (Exception ignored) {}
        }).start();
        new Thread(() -> {
            started.countDown();
            try {
                agent.chat(List.of(UserMessage.of("hello")),
                    RuntimeContext.builder().sessionId("b").build()).block();
            } catch (Exception ignored) {}
        }).start();

        started.await(2, TimeUnit.SECONDS);
        Thread.sleep(100);

        agent.interrupt(UserMessage.of("请停止"));
        blockLatch.countDown();
        Thread.sleep(200);
        assertFalse(agent.isRunning());
    }

    // ═══════════════════════════════════════════════════════════════
    // 错误时 activeRequests 清理
    // ═══════════════════════════════════════════════════════════════

    @Test
    void errorInExecutionClearsActiveRequest() {
        model.setThrowError(true);

        try {
            agent.chat(List.of(UserMessage.of("hello")),
                RuntimeContext.builder().sessionId("s1").build()).block();
        } catch (Exception ignored) {}

        assertFalse(agent.isRunning(), "Should be idle after error");
    }

    // ═══════════════════════════════════════════════════════════════
    // Stub model
    // ═══════════════════════════════════════════════════════════════

    static class StubModel extends ChatModelBase {
        private CountDownLatch blockLatch;
        private boolean throwError;

        StubModel() {
            super("test", "stub", new cd.lan1akea.core.formatter.OpenAiMessageFormatter());
        }

        void setBlockLatch(CountDownLatch latch) { this.blockLatch = latch; }
        void setThrowError(boolean v) { this.throwError = v; }

        @Override protected Map<String, String> buildAuthHeaders() { return Map.of(); }
        @Override protected String buildApiUrl() { return "http://localhost/stub"; }
        @Override public int getMaxInputTokens() { return 8192; }

        @Override
        protected Mono<ChatResponse> doChat(List<Map<String, Object>> messages,
                                             List<ToolSchema> toolSchemas,
                                             GenerateOptions options) {
            if (throwError) return Mono.error(new RuntimeException("模拟错误"));
            if (blockLatch != null) {
                try { blockLatch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            var msg = AssistantMessage.of("stub response");
            return Mono.just(new ChatResponse(msg, new ChatUsage(0, 0), "stop", "stub"));
        }

        @Override
        protected Flux<ChatStreamChunk> doStream(List<Map<String, Object>> messages,
                                                  List<ToolSchema> toolSchemas,
                                                  GenerateOptions options) {
            if (throwError) return Flux.error(new RuntimeException("模拟错误"));
            return Flux.just(ChatStreamChunk.builder().delta("stub").type(ChatStreamChunk.TYPE_TEXT)
                .finishReason("stop").build());
        }
    }
}
