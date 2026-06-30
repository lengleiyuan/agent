package cd.lan1akea.core.agent.loop;
import java.util.Set;

import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.message.*;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.tool.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReActLoop 完整单元测试。
 * 覆盖推理、工具调用、流式、中断、边界条件。
 */
class ReActLoopTest {

    private ReActLoop loop;
    private ToolRegistry toolRegistry;
    private StubChatModel model;

    @BeforeEach
    void setUp() {
        model = new StubChatModel();
        toolRegistry = new ToolRegistry();
        HookChain chain = new HookChain();
        HookDispatcher dispatcher = new HookDispatcher(chain);

        loop = new ReActLoop(model, new ToolExecutor(toolRegistry),
            dispatcher, toolRegistry);
    }

    // ========================================================================
    // 推理阶段
    // ========================================================================

    @Test
    void testReasoningWithoutTools() {
        model.setResponse(new ChatResponse(
            AssistantMessage.of("Hello, I am an AI"),
            new ChatUsage(10, 5), "stop", null));

        LoopContext ctx = buildContext(List.of(UserMessage.of("Hi")));
        ChatResponse response = loop.reasoning(ctx).block();

        assertNotNull(response);
        assertEquals("Hello, I am an AI", response.getMessage().getTextContent());
    }

    @Test
    void testReasoningWithTools() {
        toolRegistry.register(new EchoTool());
        model.setResponse(new ChatResponse(
            AssistantMessage.of("I will use echo"),
            new ChatUsage(10, 5), "stop", null));

        LoopContext ctx = buildContext(List.of(UserMessage.of("echo hello")));
        ChatResponse response = loop.reasoning(ctx).block();

        assertNotNull(response);
        assertEquals("I will use echo", response.getMessage().getTextContent());
    }

    @Test
    void testReasoningHookAbort() {
        HookChain chain = new HookChain();
        chain.register(new Hook() {
            @Override public String getName() { return "blocker"; }
            @Override public Set<HookEventType> getSubscribedEventTypes() { return Set.of(HookEventType.PRE_REASONING); }
            @Override public int getPriority() { return 1; }
            @Override
            public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
                return Mono.just(HookResult.abort("reasoning blocked"));
            }
        });

        ReActLoop l = new ReActLoop(model, new ToolExecutor(toolRegistry),
            new HookDispatcher(chain), toolRegistry);

        LoopContext ctx = buildContext(List.of(UserMessage.of("Hi")));
        assertThrows(Exception.class, () -> l.reasoning(ctx).block());
    }

    @Test
    void testReasoningHookInterrupt() {
        HookChain chain = new HookChain();
        chain.register(new Hook() {
            @Override public String getName() { return "pauser"; }
            @Override public Set<HookEventType> getSubscribedEventTypes() { return Set.of(HookEventType.PRE_REASONING); }
            @Override public int getPriority() { return 1; }
            @Override
            public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
                return Mono.just(HookResult.interrupt("needs review"));
            }
        });

        ReActLoop l = new ReActLoop(model, new ToolExecutor(toolRegistry),
            new HookDispatcher(chain), toolRegistry);

        LoopContext ctx = buildContext(List.of(UserMessage.of("Hi")));
        ChatResponse response = l.reasoning(ctx).block();

        assertNotNull(response);
        assertTrue(response.getMessage().getTextContent().contains("中断"));
        assertTrue(response.getMessage().getTextContent().contains("needs review"));
    }

    // ========================================================================
    // 流式推理
    // ========================================================================

    @Test
    void testReasoningStream() {
        model.setStreamChunks(List.of(
            ChatStreamChunk.builder().delta("Hello").type(ChatStreamChunk.TYPE_TEXT).build(),
            ChatStreamChunk.builder().delta(" streaming").type(ChatStreamChunk.TYPE_TEXT)
                .finishReason("stop").build()));

        LoopContext ctx = buildStreamContext(List.of(UserMessage.of("Hi")));
        List<ChatStreamChunk> chunks = loop.reasoningStream(ctx).collectList().block();

        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());
        String collected = chunks.stream()
            .filter(c -> c.getDelta() != null)
            .map(ChatStreamChunk::getDelta)
            .reduce("", String::concat);
        assertEquals("Hello streaming", collected);
    }

    // ========================================================================
    // 主循环
    // ========================================================================

    @Test
    void testFullReActLoop() {
        toolRegistry.register(new EchoTool());

        Msg toolCallMsg = AssistantMessage.builder()
            .addToolUse("tc1", "echo", "{\"input\":\"hello\"}")
            .build();
        model.setResponses(List.of(
            new ChatResponse(toolCallMsg, new ChatUsage(5, 10), "tool_calls", null),
            new ChatResponse(AssistantMessage.of("Echo result: ECHO: hello"),
                new ChatUsage(5, 5), "stop", null)));

        LoopContext ctx = buildContext(List.of(UserMessage.of("echo hello please")));
        ChatResponse response = loop.execute(ctx).block();

        assertNotNull(response);
        assertTrue(response.getMessage().getTextContent().contains("ECHO"));
    }

    @Test
    void testReActLoopMaxIterations() {
        model.setResponses(List.of(
            new ChatResponse(AssistantMessage.builder().addToolUse("t1", "noop", "{}").build(),
                new ChatUsage(1, 1), "tool_calls", null),
            new ChatResponse(AssistantMessage.builder().addToolUse("t2", "noop", "{}").build(),
                new ChatUsage(1, 1), "tool_calls", null),
            new ChatResponse(AssistantMessage.of("done"), new ChatUsage(1, 1), "stop", null)));

        LoopContext ctx = LoopContext.builder()
            .agentName("test").sessionId("1").messages(List.of(UserMessage.of("loop")))
            .generateOptions(GenerateOptions.builder().maxTokens(100).build())
            .maxIterations(1).stream(false).build();

        ChatResponse response = loop.execute(ctx).block();
        assertNotNull(response);
    }

    @Test
    void testExecuteWithInterruptedContext() {
        LoopContext ctx = buildContext(List.of(UserMessage.of("Hi")));
        ctx.interrupt();

        ChatResponse response = loop.execute(ctx).block();
        assertNotNull(response);
        assertTrue(response.getMessage().getTextContent().contains("中断"));
    }

    @Test
    void testInterruptWithFeedbackContinuesLoop() {
        // 第一轮：LLM 返回 tool_use
        Msg toolCallMsg = AssistantMessage.builder()
            .addToolUse("tc1", "echo", "{\"input\":\"hello\"}")
            .build();
        // 第二轮：feedback 后 LLM 正常回复
        model.setResponses(List.of(
            new ChatResponse(toolCallMsg, new ChatUsage(5, 10), "tool_calls", null),
            new ChatResponse(AssistantMessage.of("已处理你的反馈"),
                new ChatUsage(5, 5), "stop", null)));

        LoopContext ctx = buildContext(List.of(UserMessage.of("Hi")));
        // 模拟中途注入 feedback
        ctx.interrupt(UserMessage.of("等一下，换个方式回答"));

        toolRegistry.register(new EchoTool());
        ChatResponse response = loop.execute(ctx).block();

        assertNotNull(response);
        assertEquals("已处理你的反馈", response.getMessage().getTextContent());
    }

    @Test
    void testInterruptWithoutFeedbackStops() {
        model.setResponse(new ChatResponse(
            AssistantMessage.of("之前的回复"),
            new ChatUsage(5, 5), "stop", null));

        LoopContext ctx = buildContext(List.of(UserMessage.of("Hi")));
        ctx.setLastResponse(new ChatResponse(
            AssistantMessage.of("之前的回复"), null, null, null));
        ctx.interrupt(); // 无 feedback

        ChatResponse response = loop.execute(ctx).block();
        assertNotNull(response);
        assertEquals("之前的回复", response.getMessage().getTextContent());
    }

    @Test
    void testFullReActLoopStream() {
        model.setStreamChunks(List.of(
            ChatStreamChunk.builder().delta("answer without tools")
                .type(ChatStreamChunk.TYPE_TEXT).finishReason("stop").build()));

        LoopContext ctx = buildStreamContext(List.of(UserMessage.of("question")));
        List<ChatStreamChunk> chunks = loop.executeStream(ctx).collectList().block();

        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());
    }

    // ========================================================================
    // 错误处理
    // ========================================================================

    @Test
    void testHandleError() {
        LoopContext ctx = buildContext(List.of());
        loop.handleError(ctx, new RuntimeException("test error")).block();
        assertNotNull(ctx);
    }

    @Test
    void testBuildInterruptedResponse() {
        ChatResponse response = loop.buildInterrupted("manual stop");
        assertNotNull(response);
        assertTrue(response.getMessage().getTextContent().contains("中断"));
        assertTrue(response.getMessage().getTextContent().contains("manual stop"));
    }

    // ========================================================================
    // Hook 上下文构建
    // ========================================================================

    @Test
    void testHookContextBuilding() {
        LoopContext ctx = buildContext(List.of());
        HookContext hc = loop.buildHookContext(ctx);

        assertEquals("test-agent", hc.getAgentName());
        assertEquals("t1", hc.getTenantId());
        assertEquals("u1", hc.getUserId());
        assertEquals(0, hc.getCurrentIteration());
    }

    // ========================================================================
    // 循环上下文
    // ========================================================================

    @Test
    void testLoopContextMessagesAccumulate() {
        LoopContext ctx = buildContext(List.of(UserMessage.of("msg1")));
        ctx.addMessage(AssistantMessage.of("reply1"));
        ctx.addMessage(UserMessage.of("msg2"));

        assertEquals(3, ctx.getMessages().size());
    }

    @Test
    void testLoopContextIterationTracking() {
        LoopContext ctx = LoopContext.builder()
            .agentName("test").messages(List.of()).generateOptions(GenerateOptions.defaults())
            .maxIterations(3).stream(false).build();

        assertEquals(3, ctx.getMaxIterations());
        assertEquals(0, ctx.getIteration());
        ctx.setIteration(2);
        assertEquals(2, ctx.getIteration());
    }

    @Test
    void testLoopContextTokensAccumulate() {
        LoopContext ctx = buildContext(List.of());
        ctx.addTokens(100);
        ctx.addTokens(50);
        assertEquals(150, ctx.getTotalTokens());
    }

    @Test
    void testLoopContextLastResponse() {
        LoopContext ctx = buildContext(List.of());
        ChatResponse resp = new ChatResponse(
            AssistantMessage.of("hi"), null, null, null);
        ctx.setLastResponse(resp);
        assertSame(resp, ctx.getLastResponse());
    }

    @Test
    void testLoopContextInterruptWithFeedback() {
        LoopContext ctx = buildContext(List.of());
        Msg feedback = UserMessage.of("stop this");
        ctx.interrupt(feedback);
        assertTrue(ctx.isInterrupted());
        assertSame(feedback, ctx.getFeedbackMsg());
    }

    // ========================================================================
    // 流式 chunk 重组
    // ========================================================================

    @Test
    void testBuildResponseFromChunks() {
        List<ChatStreamChunk> chunks = List.of(
            ChatStreamChunk.builder().delta("Hello").type(ChatStreamChunk.TYPE_TEXT).build(),
            ChatStreamChunk.builder().delta(" World").type(ChatStreamChunk.TYPE_TEXT).build(),
            ChatStreamChunk.builder().delta(null).finishReason("stop").build());

        ChatResponse resp = loop.buildResponseFromChunks(chunks);
        assertNotNull(resp);
        assertEquals("Hello World", resp.getMessage().getTextContent());
        assertEquals("stop", resp.getFinishReason());
    }

    @Test
    void testBuildResponseFromChunksNull() {
        assertNull(loop.buildResponseFromChunks(null));
        assertNull(loop.buildResponseFromChunks(List.of()));
    }

    @Test
    void testBuildResponseFromChunksWithToolUse() {
        List<ChatStreamChunk> chunks = List.of(
            ChatStreamChunk.builder().toolUseId("tc1").toolName("calc")
                .type(ChatStreamChunk.TYPE_TOOL_USE_START).build(),
            ChatStreamChunk.builder().toolUseId("tc1").delta("{\"expr\":\"1+1\"}")
                .type(ChatStreamChunk.TYPE_TOOL_USE_DELTA).build(),
            ChatStreamChunk.builder().finishReason("tool_calls").build());

        ChatResponse resp = loop.buildResponseFromChunks(chunks);
        assertNotNull(resp);
        assertFalse(resp.getMessage().getToolUseBlocks().isEmpty());
        assertEquals("calc", resp.getMessage().getToolUseBlocks().get(0).getName());
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private LoopContext buildContext(List<Msg> messages) {
        return LoopContext.builder()
            .agentName("test-agent").tenantId("t1").userId("u1").sessionId("1")
            .messages(messages).generateOptions(GenerateOptions.defaults())
            .maxIterations(10).stream(false).build();
    }

    private LoopContext buildStreamContext(List<Msg> messages) {
        return LoopContext.builder()
            .agentName("test-agent").tenantId("t1").userId("u1").sessionId("1")
            .messages(messages).generateOptions(GenerateOptions.defaults())
            .maxIterations(10).stream(true).build();
    }

    // ========================================================================
    // Test Tool
    // ========================================================================

    static class EchoTool implements Tool {
        @Override public String getName() { return "echo"; }
        @Override public String getDescription() { return "echoes input"; }

        @Override
        public ToolSchema getParameters() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("input", Map.of("type", "string"));
            schema.put("properties", props);
            return new ToolSchema("echo", "echoes input", schema);
        }

        @Override
        public Mono<ToolResult> execute(ToolCallContext params) {
            return Mono.just(ToolResult.success("ECHO: " + params.getString("input")));
        }
    }

    // ========================================================================
    // Stub Model
    // ========================================================================

    static class StubChatModel extends ChatModelBase {
        private ChatResponse response;
        private List<ChatResponse> responses;
        private List<ChatStreamChunk> streamChunks;
        private int callCount;

        StubChatModel() {
            super("test", "stub", msgs -> List.of(Map.of("role", "user", "content", "test")));
        }

        void setResponse(ChatResponse r) { this.response = r; }
        void setResponses(List<ChatResponse> rs) { this.responses = rs; this.callCount = 0; }
        void setStreamChunks(List<ChatStreamChunk> chunks) { this.streamChunks = chunks; }

        @Override protected Map<String, String> buildAuthHeaders() { return Map.of(); }
        @Override protected String buildApiUrl() { return "http://localhost/stub"; }

        @Override
        protected Mono<ChatResponse> doChat(List<Map<String, Object>> messages,
                                            List<ToolSchema> toolSchemas,
                                            GenerateOptions options) {
            if (responses != null && callCount < responses.size()) {
                return Mono.just(responses.get(callCount++));
            }
            return Mono.justOrEmpty(response);
        }

        @Override
        protected Flux<ChatStreamChunk> doStream(List<Map<String, Object>> messages,
                                                  List<ToolSchema> toolSchemas,
                                                  GenerateOptions options) {
            if (streamChunks != null) return Flux.fromIterable(streamChunks);
            return Flux.empty();
        }
    }
}
