package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.approval.ApprovalStore;
import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.message.*;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.tool.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 流式路径 Hook 错误传播的全面测试。
 *
 * 验证 P2 改动：fire-and-forget subscribe() 替换为链式错误传播。
 * POST_MODEL_CALL / POST_REASONING 失败 → 流终止并传播错误。
 * AFTER_ITERATION 失败 → 降级为日志告警，不中断流。
 */
class ReActLoopStreamHookTest {

    private StubStreamChatModel model;
    private ToolRegistry toolRegistry;
    private HookChain hookChain;
    private HookDispatcher hookDispatcher;
    private ReActLoop loop;

    @BeforeEach
    void setUp() {
        model = new StubStreamChatModel();
        toolRegistry = new ToolRegistry();
        hookChain = new HookChain();
        hookDispatcher = new HookDispatcher(hookChain);
        loop = new ReActLoop(model, new ToolExecutor(toolRegistry), hookDispatcher, toolRegistry);
    }

    // ═══════════════════════════════════════════════════════════════
    // POST_MODEL_CALL 失败 → 错误传播
    // ═══════════════════════════════════════════════════════════════

    @Test
    void postModelCallFailurePropagatesErrorInStream() {
        RuntimeException expected = new RuntimeException("POST_MODEL_CALL 模拟失败");
        AtomicBoolean postModelCalled = new AtomicBoolean(false);
        AtomicBoolean postReasoningCalled = new AtomicBoolean(false);

        hookChain.register(new FailingHook("failing-post-model",
            Set.of(HookEventType.POST_MODEL_CALL), expected, postModelCalled));
        hookChain.register(new TrackingHook("post-reasoning-tracker",
            Set.of(HookEventType.POST_REASONING), postReasoningCalled));

        List<Msg> messages = List.of(UserMessage.of("hello"));
        LoopContext ctx = buildCtx(messages, 1);

        List<ChatStreamChunk> chunks = new ArrayList<>();
        try {
            loop.reasoningStream(ctx).doOnNext(chunks::add).blockLast();
            fail("Expected error was not thrown");
        } catch (RuntimeException e) {
            assertSame(expected, e);
        }
        assertTrue(postModelCalled.get(), "POST_MODEL_CALL hook should have been called");
        assertFalse(postReasoningCalled.get(), "POST_REASONING hook should NOT be called after error");
    }

    // ═══════════════════════════════════════════════════════════════
    // POST_REASONING 失败 → 错误传播
    // ═══════════════════════════════════════════════════════════════

    @Test
    void postReasoningFailurePropagatesErrorInStream() {
        RuntimeException expected = new RuntimeException("POST_REASONING 模拟失败");
        AtomicBoolean postModelCalled = new AtomicBoolean(false);
        AtomicBoolean postReasoningCalled = new AtomicBoolean(false);

        hookChain.register(new TrackingHook("post-model-tracker",
            Set.of(HookEventType.POST_MODEL_CALL), postModelCalled));
        hookChain.register(new FailingHook("failing-post-reasoning",
            Set.of(HookEventType.POST_REASONING), expected, postReasoningCalled));

        List<Msg> messages = List.of(UserMessage.of("hello"));
        LoopContext ctx = buildCtx(messages, 1);

        try {
            loop.reasoningStream(ctx).blockLast();
            fail("Expected error was not thrown");
        } catch (RuntimeException e) {
            assertSame(expected, e);
        }
        assertTrue(postModelCalled.get(), "POST_MODEL_CALL hook should have been called");
        assertTrue(postReasoningCalled.get(), "POST_REASONING hook should have been called");
    }

    // ═══════════════════════════════════════════════════════════════
    // KB 绕过 → 不触发 POST hooks
    // ═══════════════════════════════════════════════════════════════

    @Test
    void kBypassDoesNotTriggerPostHooks() {
        AtomicBoolean postModelCalled = new AtomicBoolean(false);
        AtomicBoolean postReasoningCalled = new AtomicBoolean(false);
        AtomicBoolean preReasoningCalled = new AtomicBoolean(false);

        // PRE_REASONING hook 注入 bypass message（KB 命中）
        hookChain.register(new BypassHook("kb-bypass", preReasoningCalled,
            AssistantMessage.of("从 KB 直接回答")));
        hookChain.register(new TrackingHook("post-model-tracker",
            Set.of(HookEventType.POST_MODEL_CALL), postModelCalled));
        hookChain.register(new TrackingHook("post-reasoning-tracker",
            Set.of(HookEventType.POST_REASONING), postReasoningCalled));

        List<Msg> messages = List.of(UserMessage.of("已知问题"));
        LoopContext ctx = buildCtx(messages, 1);

        List<ChatStreamChunk> chunks = loop.reasoningStream(ctx).collectList().block();
        assertNotNull(chunks);
        assertEquals(1, chunks.size());
        assertEquals("从 KB 直接回答", chunks.get(0).getDelta());
        assertTrue(preReasoningCalled.get());
        assertFalse(postModelCalled.get(), "Bypass should skip POST_MODEL_CALL");
        assertFalse(postReasoningCalled.get(), "Bypass should skip POST_REASONING");
    }

    // ═══════════════════════════════════════════════════════════════
    // 正常流式推理 → POST hooks 正确触发
    // ═══════════════════════════════════════════════════════════════

    @Test
    void normalStreamTriggersBothPostHooks() {
        AtomicBoolean postModelCalled = new AtomicBoolean(false);
        AtomicBoolean postReasoningCalled = new AtomicBoolean(false);

        hookChain.register(new TrackingHook("post-model",
            Set.of(HookEventType.POST_MODEL_CALL), postModelCalled));
        hookChain.register(new TrackingHook("post-reasoning",
            Set.of(HookEventType.POST_REASONING), postReasoningCalled));

        List<Msg> messages = List.of(UserMessage.of("hello"));
        LoopContext ctx = buildCtx(messages, 1);

        List<ChatStreamChunk> respChunks = List.of(
            ChatStreamChunk.builder().delta("response text").type(ChatStreamChunk.TYPE_TEXT)
                .finishReason("stop").build()
        );
        model.setChunks(respChunks);

        List<ChatStreamChunk> chunks = loop.reasoningStream(ctx).collectList().block();
        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());
        assertTrue(postModelCalled.get(), "POST_MODEL_CALL should fire");
        assertTrue(postReasoningCalled.get(), "POST_REASONING should fire");
    }

    // ═══════════════════════════════════════════════════════════════
    // PRE_REASONING ABORT → 流终止
    // ═══════════════════════════════════════════════════════════════

    @Test
    void preReasoningAbortTerminatesStream() {
        hookChain.register(new AbortHook("abort-hook",
            Set.of(HookEventType.PRE_REASONING), "测试中止"));

        List<Msg> messages = List.of(UserMessage.of("hello"));
        LoopContext ctx = buildCtx(messages, 1);

        try {
            loop.reasoningStream(ctx).blockLast();
            fail("Expected HookAbortException");
        } catch (cd.lan1akea.core.exception.HookAbortException e) {
            assertTrue(e.getMessage().contains("测试中止"), "Expected message to contain '测试中止', got: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PRE_REASONING INTERRUPT → 返回中断消息
    // ═══════════════════════════════════════════════════════════════

    @Test
    void preReasoningInterruptReturnsInterruptedChunk() {
        hookChain.register(new InterruptHook("interrupt-hook",
            Set.of(HookEventType.PRE_REASONING), "需要人工介入"));

        List<Msg> messages = List.of(UserMessage.of("hello"));
        LoopContext ctx = buildCtx(messages, 1);

        List<ChatStreamChunk> chunks = loop.reasoningStream(ctx).collectList().block();
        assertNotNull(chunks);
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).getDelta().contains("需要人工介入"));
    }

    // ═══════════════════════════════════════════════════════════════
    // PRE_MODEL_CALL ABORT → 流终止
    // ═══════════════════════════════════════════════════════════════

    @Test
    void preModelCallAbortTerminatesStream() {
        hookChain.register(new AbortHook("model-abort",
            Set.of(HookEventType.PRE_MODEL_CALL), "模型调用被拒绝"));

        List<Msg> messages = List.of(UserMessage.of("hello"));
        LoopContext ctx = buildCtx(messages, 1);

        try {
            loop.reasoningStream(ctx).blockLast();
            fail("Expected HookAbortException");
        } catch (cd.lan1akea.core.exception.HookAbortException e) {
            assertTrue(e.getMessage().contains("模型调用被拒绝"), "Expected message to contain '模型调用被拒绝', got: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // AFTER_ITERATION 失败 → 日志告警，不中断流
    // ═══════════════════════════════════════════════════════════════

    @Test
    void afterIterationFailureIsSwallowed() {
        RuntimeException expected = new RuntimeException("持久化失败");
        AtomicBoolean afterCalled = new AtomicBoolean(false);

        hookChain.register(new FailingHook("failing-after-iteration",
            Set.of(HookEventType.AFTER_ITERATION), expected, afterCalled));

        List<Msg> messages = List.of(UserMessage.of("hello"));
        LoopContext ctx = buildCtx(messages, 1);

        // dispatchAfterIteration 不应抛异常
        loop.dispatchAfterIteration(ctx).block();
        assertTrue(afterCalled.get(), "AFTER_ITERATION hook should be called");
    }

    // ═══════════════════════════════════════════════════════════════
    // AFTER_ITERATION 正常执行
    // ═══════════════════════════════════════════════════════════════

    @Test
    void afterIterationNormalExecution() {
        AtomicBoolean afterCalled = new AtomicBoolean(false);

        hookChain.register(new TrackingHook("after-iteration",
            Set.of(HookEventType.AFTER_ITERATION), afterCalled));

        List<Msg> messages = List.of(UserMessage.of("hello"));
        LoopContext ctx = buildCtx(messages, 1);

        loop.dispatchAfterIteration(ctx).block();
        assertTrue(afterCalled.get(), "AFTER_ITERATION hook should be called");
    }

    // ═══════════════════════════════════════════════════════════════
    // 多迭代场景 — POST hooks 在每次迭代中触发
    // ═══════════════════════════════════════════════════════════════

    @Test
    void postHooksFireEachIterationInStream() {
        AtomicInteger postModelCount = new AtomicInteger(0);
        AtomicInteger postReasoningCount = new AtomicInteger(0);

        hookChain.register(new CountingHook("post-model-counter",
            Set.of(HookEventType.POST_MODEL_CALL), postModelCount));
        hookChain.register(new CountingHook("post-reasoning-counter",
            Set.of(HookEventType.POST_REASONING), postReasoningCount));

        List<ChatStreamChunk> respChunks = List.of(
            ChatStreamChunk.builder().delta("text").type(ChatStreamChunk.TYPE_TEXT)
                .finishReason("stop").build()
        );
        model.setChunks(respChunks);

        List<Msg> messages = List.of(UserMessage.of("hello"));
        LoopContext ctx = buildCtx(messages, 1);

        loop.reasoningStream(ctx).blockLast();
        assertEquals(1, postModelCount.get(), "POST_MODEL_CALL should fire once");
        assertEquals(1, postReasoningCount.get(), "POST_REASONING should fire once");
    }

    // ═══════════════════════════════════════════════════════════════
    // 无 Hook → 流正常完成
    // ═══════════════════════════════════════════════════════════════

    @Test
    void noHooksStreamCompletesNormally() {
        List<ChatStreamChunk> respChunks = List.of(
            ChatStreamChunk.builder().delta("hello world").type(ChatStreamChunk.TYPE_TEXT)
                .finishReason("stop").build()
        );
        model.setChunks(respChunks);

        List<Msg> messages = List.of(UserMessage.of("hello"));
        LoopContext ctx = buildCtx(messages, 1);

        List<ChatStreamChunk> chunks = loop.reasoningStream(ctx).collectList().block();
        assertNotNull(chunks);
        assertEquals(1, chunks.size());
        assertEquals("hello world", chunks.get(0).getDelta());
    }

    // ═══════════════════════════════════════════════════════════════
    // 空 Hook 链 → 行为不变
    // ═══════════════════════════════════════════════════════════════

    @Test
    void emptyHookChainStreamCompletesNormally() {
        List<ChatStreamChunk> respChunks = List.of(
            ChatStreamChunk.builder().delta("ok").type(ChatStreamChunk.TYPE_TEXT)
                .finishReason("stop").build()
        );
        model.setChunks(respChunks);

        List<Msg> messages = List.of(UserMessage.of("hello"));
        LoopContext ctx = buildCtx(messages, 1);

        List<ChatStreamChunk> chunks = loop.reasoningStream(ctx).collectList().block();
        assertNotNull(chunks);
        assertEquals(1, chunks.size());
        assertEquals("ok", chunks.get(0).getDelta());
    }

    // ═══════════════════════════════════════════════════════════════
    // 并发流式调用 — Hook 状态隔离
    // ═══════════════════════════════════════════════════════════════

    @Test
    void concurrentStreamCallsDontInterfere() throws Exception {
        int threads = 8;
        AtomicInteger postCount = new AtomicInteger(0);
        CopyOnWriteArrayList<String> errors = new CopyOnWriteArrayList<>();

        hookChain.register(new CountingHook("post-model",
            Set.of(HookEventType.POST_MODEL_CALL), postCount));

        List<ChatStreamChunk> respChunks = List.of(
            ChatStreamChunk.builder().delta("text").type(ChatStreamChunk.TYPE_TEXT)
                .finishReason("stop").build()
        );
        model.setChunks(respChunks);

        Thread[] threadArr = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            threadArr[i] = new Thread(() -> {
                try {
                    List<Msg> messages = List.of(UserMessage.of("msg-" + idx));
                    LoopContext ctx = buildCtx(messages, 1);
                    loop.reasoningStream(ctx).blockLast();
                } catch (Exception e) {
                    errors.add(e.getMessage());
                }
            });
        }

        for (Thread t : threadArr) t.start();
        for (Thread t : threadArr) t.join();

        assertTrue(errors.isEmpty(), "No errors expected: " + errors);
        assertEquals(threads, postCount.get(), "All POST_MODEL_CALL hooks should fire");
    }

    // ═══════════════════════════════════════════════════════════════
    // 工具调用流式 → POST_TOOL_CALL 正常触发
    // ═══════════════════════════════════════════════════════════════

    @Test
    void toolCallPostHookFiresInActingStream() {
        AtomicBoolean preToolCalled = new AtomicBoolean(false);
        AtomicBoolean postToolCalled = new AtomicBoolean(false);

        hookChain.register(new TrackingHook("pre-tool",
            Set.of(HookEventType.PRE_TOOL_CALL), preToolCalled));
        hookChain.register(new TrackingHook("post-tool",
            Set.of(HookEventType.POST_TOOL_CALL), postToolCalled));

        toolRegistry.register(new EchoTool());

        List<ChatStreamChunk> respChunks = List.of(
            ChatStreamChunk.builder().delta("calc").toolName("echo")
                .type(ChatStreamChunk.TYPE_TOOL_USE_START).toolUseId("t1").build(),
            ChatStreamChunk.builder().delta("{\"input\":\"hi\"}")
                .type(ChatStreamChunk.TYPE_TOOL_USE_DELTA).toolUseId("t1").build()
        );
        model.setChunks(respChunks);

        List<Msg> messages = List.of(UserMessage.of("echo hi"));
        LoopContext ctx = buildCtx(messages, 3);

        List<ChatStreamChunk> chunks = loop.executeStream(ctx).collectList().block();
        assertNotNull(chunks);
        assertTrue(preToolCalled.get(), "PRE_TOOL_CALL should fire");
        assertTrue(postToolCalled.get(), "POST_TOOL_CALL should fire");
    }

    // ═══════════════════════════════════════════════════════════════
    // 流式执行 — 多迭代时 dispatchAfterIteration 正确链入
    // ═══════════════════════════════════════════════════════════════

    @Test
    void streamingMultiIterationDispatchesAfterEachIteration() {
        AtomicInteger afterIterCount = new AtomicInteger(0);

        hookChain.register(new CountingHook("after-iter",
            Set.of(HookEventType.AFTER_ITERATION), afterIterCount));

        toolRegistry.register(new EchoTool());

        // 两轮迭代：第一轮 tool call + 第二轮 text
        List<ChatStreamChunk> iter1 = List.of(
            ChatStreamChunk.builder().delta("echo").toolName("echo")
                .type(ChatStreamChunk.TYPE_TOOL_USE_START).toolUseId("t1").build(),
            ChatStreamChunk.builder().delta("{\"input\":\"hi\"}")
                .type(ChatStreamChunk.TYPE_TOOL_USE_DELTA).toolUseId("t1").build()
        );
        List<ChatStreamChunk> iter2 = List.of(
            ChatStreamChunk.builder().delta("done").type(ChatStreamChunk.TYPE_TEXT)
                .finishReason("stop").build()
        );
        model.setChunkSequences(List.of(iter1, iter2));

        List<Msg> messages = List.of(UserMessage.of("echo hi"));
        LoopContext ctx = buildCtx(messages, 3);

        List<ChatStreamChunk> chunks = loop.executeStream(ctx).collectList().block();
        assertNotNull(chunks);
        assertEquals(2, afterIterCount.get(), "AFTER_ITERATION should fire twice for 2 iterations");
    }

    // ═══════════════════════════════════════════════════════════════
    // 非流式 — dispatchAfterIteration 正常链入
    // ═══════════════════════════════════════════════════════════════

    @Test
    void nonStreamingAfterIterationIsChained() {
        AtomicInteger afterIterCount = new AtomicInteger(0);

        hookChain.register(new CountingHook("after-iter",
            Set.of(HookEventType.AFTER_ITERATION), afterIterCount));

        toolRegistry.register(new EchoTool());

        // 两轮：第一轮 tool call，第二轮 text
        List<List<ChatStreamChunk>> sequences = List.of(
            List.of(
                ChatStreamChunk.builder().delta("echo").toolName("echo")
                    .type(ChatStreamChunk.TYPE_TOOL_USE_START).toolUseId("t1").build(),
                ChatStreamChunk.builder().delta("{\"input\":\"hi\"}")
                    .type(ChatStreamChunk.TYPE_TOOL_USE_DELTA).toolUseId("t1").build()
            ),
            List.of(
                ChatStreamChunk.builder().delta("done").type(ChatStreamChunk.TYPE_TEXT)
                    .finishReason("stop").build()
            )
        );
        model.setChunkSequences(sequences);

        List<Msg> messages = List.of(UserMessage.of("echo hi"));
        LoopContext ctx = buildCtx(messages, 2);

        loop.execute(ctx).block();
        assertEquals(1, afterIterCount.get(), "AFTER_ITERATION should fire");
    }

    // ═══════════════════════════════════════════════════════════════
    // Helper methods
    // ═══════════════════════════════════════════════════════════════

    private LoopContext buildCtx(List<Msg> messages, int maxIterations) {
        return LoopContext.builder()
            .agentName("TestAgent")
            .tenantId("test-tenant")
            .sessionId("test-session")
            .messages(messages)
            .generateOptions(GenerateOptions.defaults())
            .maxIterations(maxIterations)
            .stream(true)
            .build();
    }

    // ═══════════════════════════════════════════════════════════════
    // Stub helpers
    // ═══════════════════════════════════════════════════════════════

    static class StubStreamChatModel extends ChatModelBase {
        private List<ChatStreamChunk> chunks = List.of();
        private List<List<ChatStreamChunk>> chunkSequences;
        private int callCount;

        StubStreamChatModel() {
            super("test", "stub-stream", new cd.lan1akea.core.formatter.OpenAiMessageFormatter());
        }

        void setChunks(List<ChatStreamChunk> chunks) { this.chunks = chunks; }
        void setResponse(ChatResponse resp) {
            this.chunks = List.of(
                ChatStreamChunk.builder().delta(resp.getMessage().getTextContent())
                    .type(ChatStreamChunk.TYPE_TEXT).finishReason("stop").build());
        }

        void setChunkSequences(List<List<ChatStreamChunk>> sequences) {
            this.chunkSequences = sequences;
            this.callCount = 0;
        }

        @Override
        protected Map<String, String> buildAuthHeaders() { return Map.of(); }
        @Override
        protected String buildApiUrl() { return "http://localhost/stub"; }
        @Override
        public int getMaxInputTokens() { return 8192; }

        @Override
        protected Mono<ChatResponse> doChat(List<Map<String, Object>> messages,
                                             List<ToolSchema> toolSchemas,
                                             GenerateOptions options) {
            List<ChatStreamChunk> seq;
            if (chunkSequences != null && callCount < chunkSequences.size()) {
                seq = chunkSequences.get(callCount++);
            } else {
                seq = chunks;
            }
            return Mono.just(ChatResponseFromChunks(seq));
        }

        private ChatResponse ChatResponseFromChunks(List<ChatStreamChunk> cks) {
            if (cks.isEmpty()) return new ChatResponse(AssistantMessage.of("empty"), new ChatUsage(0, 0), "stop", "stub");
            StringBuilder sb = new StringBuilder();
            Map<String, String> toolNames = new LinkedHashMap<>();
            Map<String, String> toolArgs = new LinkedHashMap<>();
            String finishReason = "stop";
            for (ChatStreamChunk c : cks) {
                if (c.getFinishReason() != null) finishReason = c.getFinishReason();
                if (c.getDelta() != null && ChatStreamChunk.TYPE_TEXT.equals(c.getType())) sb.append(c.getDelta());
                if (ChatStreamChunk.TYPE_TOOL_USE_START.equals(c.getType()) && c.getToolUseId() != null) {
                    toolNames.put(c.getToolUseId(), c.getToolName() != null ? c.getToolName() : "");
                    toolArgs.put(c.getToolUseId(), "");
                }
                if (ChatStreamChunk.TYPE_TOOL_USE_DELTA.equals(c.getType()) && c.getToolUseId() != null) {
                    toolArgs.merge(c.getToolUseId(), c.getDelta() != null ? c.getDelta() : "", String::concat);
                }
            }
            cd.lan1akea.core.message.MsgBuilder builder = AssistantMessage.builder();
            if (!sb.isEmpty()) builder.addText(sb.toString());
            for (Map.Entry<String, String> e : toolArgs.entrySet()) {
                builder.addToolUse(e.getKey(), toolNames.getOrDefault(e.getKey(), ""), e.getValue());
            }
            String fr = toolArgs.isEmpty() ? finishReason : "tool_calls";
            return new ChatResponse(builder.build(), new ChatUsage(0, 0), fr, "stub");
        }

        @Override
        protected Flux<ChatStreamChunk> doStream(List<Map<String, Object>> messages,
                                                  List<ToolSchema> toolSchemas,
                                                  GenerateOptions options) {
            if (chunkSequences != null && callCount < chunkSequences.size()) {
                return Flux.fromIterable(chunkSequences.get(callCount++));
            }
            return Flux.fromIterable(chunks);
        }
    }

    /** 在指定事件类型抛出异常的 Hook。 */
    static class FailingHook implements Hook {
        private final String name;
        private final Set<HookEventType> types;
        private final RuntimeException error;
        private final AtomicBoolean called;

        FailingHook(String name, Set<HookEventType> types, RuntimeException error, AtomicBoolean called) {
            this.name = name; this.types = types; this.error = error; this.called = called;
        }

        @Override public String getName() { return name; }
        @Override public Set<HookEventType> getSubscribedEventTypes() { return types; }
        @Override
        public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
            called.set(true);
            throw error;
        }
    }

    /** 记录是否被调用的跟踪 Hook。 */
    static class TrackingHook implements Hook {
        private final String name;
        private final Set<HookEventType> types;
        private final AtomicBoolean called;

        TrackingHook(String name, Set<HookEventType> types, AtomicBoolean called) {
            this.name = name; this.types = types; this.called = called;
        }

        @Override public String getName() { return name; }
        @Override public Set<HookEventType> getSubscribedEventTypes() { return types; }
        @Override
        public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
            called.set(true);
            return Mono.just(HookResult.continue_());
        }
    }

    /** 记录调用次数的计数 Hook。 */
    static class CountingHook implements Hook {
        private final String name;
        private final Set<HookEventType> types;
        private final AtomicInteger counter;

        CountingHook(String name, Set<HookEventType> types, AtomicInteger counter) {
            this.name = name; this.types = types; this.counter = counter;
        }

        @Override public String getName() { return name; }
        @Override public Set<HookEventType> getSubscribedEventTypes() { return types; }
        @Override
        public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
            counter.incrementAndGet();
            return Mono.just(HookResult.continue_());
        }
    }

    /** KB 命中：在 PRE_REASONING 设置 bypassMessage 跳过模型调用。 */
    static class BypassHook implements Hook {
        private final String name;
        private final AtomicBoolean called;
        private final Msg bypassMsg;

        BypassHook(String name, AtomicBoolean called, Msg bypassMsg) {
            this.name = name; this.called = called; this.bypassMsg = bypassMsg;
        }

        @Override public String getName() { return name; }
        @Override public Set<HookEventType> getSubscribedEventTypes() { return Set.of(HookEventType.PRE_REASONING); }
        @Override
        public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
            called.set(true);
            if (event instanceof ReasoningEvent re) {
                re.setBypassMessage(bypassMsg);
            }
            return Mono.just(HookResult.continue_());
        }
    }

    /** 返回 ABORT 的 Hook。 */
    static class AbortHook implements Hook {
        private final String name;
        private final Set<HookEventType> types;
        private final String reason;

        AbortHook(String name, Set<HookEventType> types, String reason) {
            this.name = name; this.types = types; this.reason = reason;
        }

        @Override public String getName() { return name; }
        @Override public Set<HookEventType> getSubscribedEventTypes() { return types; }
        @Override
        public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
            return Mono.just(HookResult.abort(reason));
        }
    }

    /** 返回 INTERRUPT 的 Hook。 */
    static class InterruptHook implements Hook {
        private final String name;
        private final Set<HookEventType> types;
        private final String reason;

        InterruptHook(String name, Set<HookEventType> types, String reason) {
            this.name = name; this.types = types; this.reason = reason;
        }

        @Override public String getName() { return name; }
        @Override public Set<HookEventType> getSubscribedEventTypes() { return types; }
        @Override
        public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
            return Mono.just(HookResult.interrupt(reason));
        }
    }

    /** 简单回显工具。 */
    static class EchoTool extends ToolBase {
        EchoTool() { declareStringParam("input", "输入文本", true); }

        @Override public String getName() { return "echo"; }
        @Override public String getDescription() { return "回显输入"; }
        @Override
        public Mono<ToolResult> execute(ToolCallContext ctx) {
            return Mono.just(ToolResult.success("echo: " + ctx.getString("input")).withCallId(ctx.getCallId()));
        }
    }
}
