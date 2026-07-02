package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.message.*;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.tool.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 迭代间退避策略测试。
 *
 * 验证 P2 改动：backoffMs 配置控制迭代间延迟。
 */
class ReActLoopBackoffTest {

    private StubFastModel model;
    private ToolRegistry toolRegistry;
    private ReActLoop loop;

    @BeforeEach
    void setUp() {
        model = new StubFastModel();
        toolRegistry = new ToolRegistry();
        HookChain chain = new HookChain();
        HookDispatcher dispatcher = new HookDispatcher(chain);
        loop = new ReActLoop(model, new ToolExecutor(toolRegistry), dispatcher, toolRegistry);
    }

    @Test
    void zeroBackoffHasNoDelay() {
        toolRegistry.register(new EchoTool());
        model.setResponses(List.of(
            toolCallResp("echo", "{\"input\":\"hi\"}"),
            new ChatResponse(AssistantMessage.of("done"), new ChatUsage(0, 0), "stop", "test")));

        LoopContext ctx = LoopContext.builder()
            .agentName("TestAgent").tenantId("t1").sessionId("s1")
            .messages(List.of(UserMessage.of("echo hi")))
            .generateOptions(GenerateOptions.defaults())
            .maxIterations(5).backoffMs(0).stream(false).build();

        long start = System.currentTimeMillis();
        ChatResponse resp = loop.execute(ctx).block();
        long elapsed = System.currentTimeMillis() - start;

        assertNotNull(resp);
        assertTrue(elapsed < 500, "Zero backoff should complete quickly, took " + elapsed + "ms");
    }

    @Test
    void positiveBackoffAddsDelayBetweenIterations() {
        toolRegistry.register(new EchoTool());
        model.setResponses(List.of(
            toolCallResp("echo", "{\"input\":\"hi\"}"),
            new ChatResponse(AssistantMessage.of("done"), new ChatUsage(0, 0), "stop", "test")));

        int backoffMs = 100;
        LoopContext ctx = LoopContext.builder()
            .agentName("TestAgent").tenantId("t1").sessionId("s1")
            .messages(List.of(UserMessage.of("echo hi")))
            .generateOptions(GenerateOptions.defaults())
            .maxIterations(5).backoffMs(backoffMs).stream(false).build();

        long start = System.currentTimeMillis();
        ChatResponse resp = loop.execute(ctx).block();
        long elapsed = System.currentTimeMillis() - start;

        assertNotNull(resp);
        assertTrue(elapsed >= backoffMs - 20,
            "Should have at least backoff delay, took " + elapsed + "ms");
    }

    @Test
    void singleIterationNoBackoffApplied() {
        model.setResponse(new ChatResponse(AssistantMessage.of("done"),
            new ChatUsage(0, 0), "stop", "test"));

        LoopContext ctx = LoopContext.builder()
            .agentName("TestAgent").tenantId("t1").sessionId("s1")
            .messages(List.of(UserMessage.of("hello")))
            .generateOptions(GenerateOptions.defaults())
            .maxIterations(5).backoffMs(200).stream(false).build();

        long start = System.currentTimeMillis();
        ChatResponse resp = loop.execute(ctx).block();
        long elapsed = System.currentTimeMillis() - start;

        assertNotNull(resp);
        assertTrue(elapsed < 100, "Single iteration should NOT have backoff delay, took " + elapsed + "ms");
    }

    @Test
    void multipleToolIterationsEachWithBackoff() {
        toolRegistry.register(new EchoTool());
        model.setResponses(List.of(
            toolCallResp("echo", "{\"input\":\"a\"}"),
            toolCallResp("echo", "{\"input\":\"b\"}"),
            toolCallResp("echo", "{\"input\":\"c\"}"),
            new ChatResponse(AssistantMessage.of("done"), new ChatUsage(0, 0), "stop", "test")));

        int backoffMs = 50;
        LoopContext ctx = LoopContext.builder()
            .agentName("TestAgent").tenantId("t1").sessionId("s1")
            .messages(List.of(UserMessage.of("echo a b c")))
            .generateOptions(GenerateOptions.defaults())
            .maxIterations(10).backoffMs(backoffMs).stream(false).build();

        long start = System.currentTimeMillis();
        ChatResponse resp = loop.execute(ctx).block();
        long elapsed = System.currentTimeMillis() - start;

        assertNotNull(resp);
        assertTrue(elapsed >= backoffMs * 3 - 30,
            "Should have 3x backoff delay for 3 tool iterations, took " + elapsed + "ms");
    }

    @Test
    void defaultBackoffIsZero() {
        LoopContext ctx = LoopContext.builder()
            .agentName("TestAgent").messages(List.of()).generateOptions(GenerateOptions.defaults())
            .maxIterations(5).stream(false).build();
        assertEquals(0L, ctx.getBackoffMs(), "Default backoff should be 0");
    }

    @Test
    void backoffOnlyAppliesAfterIterationNotBeforeFirst() {
        toolRegistry.register(new EchoTool());
        model.setResponses(List.of(
            toolCallResp("echo", "{\"input\":\"x\"}"),
            new ChatResponse(AssistantMessage.of("done"), new ChatUsage(0, 0), "stop", "test")));

        LoopContext ctx = LoopContext.builder()
            .agentName("TestAgent").tenantId("t1").sessionId("s1")
            .messages(List.of(UserMessage.of("echo x")))
            .generateOptions(GenerateOptions.defaults())
            .maxIterations(5).backoffMs(500).stream(false).build();

        long start = System.currentTimeMillis();
        ChatResponse resp = loop.execute(ctx).block();
        long elapsed = System.currentTimeMillis() - start;

        assertNotNull(resp);
        assertTrue(elapsed >= 480 && elapsed < 1500,
            "Should have backoff only after first iteration, took " + elapsed + "ms");
    }

    private static ChatResponse toolCallResp(String toolName, String args) {
        return new ChatResponse(
            AssistantMessage.builder().addToolUse("t-" + System.nanoTime(), toolName, args).build(),
            new ChatUsage(0, 0), "tool_calls", "test");
    }

    static class StubFastModel extends ChatModelBase {
        private List<ChatResponse> responses = List.of();
        private ChatResponse singleResp;
        private int callCount;

        StubFastModel() { super("test", "fast", new cd.lan1akea.core.formatter.OpenAiMessageFormatter()); }
        void setResponses(List<ChatResponse> r) { this.responses = r; this.callCount = 0; }
        void setResponse(ChatResponse r) { this.singleResp = r; }

        @Override protected Map<String, String> buildAuthHeaders() { return Map.of(); }
        @Override protected String buildApiUrl() { return "http://localhost/f"; }
        @Override public int getMaxInputTokens() { return 8192; }

        @Override
        protected Mono<ChatResponse> doChat(List<Map<String, Object>> m, List<ToolSchema> s, GenerateOptions o) {
            if (singleResp != null) return Mono.just(singleResp);
            return Mono.just(callCount < responses.size() ? responses.get(callCount++) : textResp("default"));
        }
        @Override
        protected Flux<ChatStreamChunk> doStream(List<Map<String, Object>> m, List<ToolSchema> s, GenerateOptions o) {
            return Flux.just(ChatStreamChunk.builder().delta("f").type(ChatStreamChunk.TYPE_TEXT).finishReason("stop").build());
        }
    }

    static ChatResponse textResp(String text) {
        return new ChatResponse(AssistantMessage.of(text), new ChatUsage(0, 0), "stop", "fast");
    }

    static class EchoTool extends ToolBase {
        EchoTool() { declareStringParam("input", "输入", true); }
        @Override public String getName() { return "echo"; }
        @Override public String getDescription() { return "echo"; }
        @Override
        public Mono<ToolResult> execute(ToolCallContext ctx) {
            return Mono.just(ToolResult.success("echo:" + ctx.getString("input")).withCallId(ctx.getCallId()));
        }
    }
}
