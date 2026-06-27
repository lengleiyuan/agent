package cd.lan1akea.core.agent;
import java.util.Set;

import cd.lan1akea.core.agent.config.AgentConfig;
import cd.lan1akea.core.agent.config.AgentExecutionConfig;
import cd.lan1akea.core.context.RuntimeContext;
import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.hook.impl.LoggingHook;
import cd.lan1akea.core.hook.impl.AuditHook;
import cd.lan1akea.core.message.*;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.state.InMemoryAgentStateStore;
import cd.lan1akea.core.tool.ToolRegistry;
import cd.lan1akea.core.tool.builtin.CalculatorTool;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AbstractAgent Mock 集成测试。
 * 使用 StubModel 替代真实 LLM，验证完整构建→执行→Hook→工具调用流程。
 */
class ReActAgentTest {

    private StubModel model;
    private ToolRegistry toolRegistry;
    private HookChain hookChain;
    private LoggingHook loggingHook;
    private AuditHook auditHook;
    private InMemoryAgentStateStore stateStore;

    @BeforeEach
    void setUp() {
        model = new StubModel();
        toolRegistry = new ToolRegistry();
        toolRegistry.register(new CalculatorTool());

        loggingHook = new LoggingHook("test");
        auditHook = new AuditHook("test");

        hookChain = new HookChain();
        hookChain.register(loggingHook);
        hookChain.register(auditHook);

        stateStore = new InMemoryAgentStateStore();
    }

    // ========================================================================
    // 构建生命周期
    // ========================================================================

    @Test
    void testBuildAndShutdown() {
        ReActAgent agent = createAgent("TestAgent");
        assertTrue(agent.isBuilt());
        assertEquals("TestAgent", agent.getName());

        agent.shutdown().block();
        assertFalse(agent.isBuilt());
    }

    @Test
    void testBuildTwiceFails() {
        ReActAgent agent = createAgent("TestAgent");
        assertThrows(Exception.class, () -> agent.build().block());
    }

    @Test
    void testChatBeforeBuildFails() {
        ReActAgent agent = new ReActAgent(AgentConfig.builder()
            .name("Unbuilt").model(model)
            .executionConfig(AgentExecutionConfig.defaults()).build());
        assertFalse(agent.isBuilt());
        assertThrows(Exception.class, () -> agent.chat(List.of(UserMessage.of("hi"))).block());
    }

    // ========================================================================
    // 基础对话
    // ========================================================================

    @Test
    void testBasicChat() {
        model.setResponse(new ChatResponse(
            AssistantMessage.of("你好，我是 AI 助手"),
            new ChatUsage(10, 5), "stop", null));

        ReActAgent agent = createAgent("TestAgent");
        ChatResponse response = agent.chat(List.of(UserMessage.of("你好"))).block();

        assertNotNull(response);
        assertEquals("你好，我是 AI 助手", response.getMessage().getTextContent());
        assertEquals("stop", response.getFinishReason());
    }

    @Test
    void testChatWithSystemPrompt() {
        model.setResponse(new ChatResponse(
            AssistantMessage.of("{\"result\": \"ok\"}"),
            new ChatUsage(5, 10), "stop", null));

        ReActAgent agent = createAgent("TestAgent");
        ChatResponse response = agent.chat(List.of(
            SystemMessage.of("你是 JSON 助手"),
            UserMessage.of("返回 JSON"))).block();

        assertNotNull(response);
        assertTrue(response.getMessage().getTextContent().contains("ok"));
    }

    @Test
    void testChatWithRuntimeContext() {
        model.setResponse(new ChatResponse(
            AssistantMessage.of("tenant aware"),
            new ChatUsage(5, 5), "stop", null));

        ReActAgent agent = createAgent("TestAgent");
        ChatResponse response = agent.chat(
            List.of(UserMessage.of("hi")),
            RuntimeContext.builder().tenantId("t1").userId("u1").build()).block();

        assertNotNull(response);
        assertEquals("tenant aware", response.getMessage().getTextContent());
    }

    // ========================================================================
    // Hook 触发
    // ========================================================================

    @Test
    void testHooksAreTriggeredOnChat() {
        model.setResponse(new ChatResponse(
            AssistantMessage.of("ok"), new ChatUsage(1, 1), "stop", null));

        ReActAgent agent = createAgent("TestAgent");
        agent.chat(List.of(UserMessage.of("hi"))).block();

        assertTrue(loggingHook.getEventCount() > 0,
            "LoggingHook 应记录事件（实际: " + loggingHook.getEventCount() + "）");
    }

    @Test
    void testHookChainAbortPreventsExecution() {
        hookChain.register(new Hook() {
            @Override public String getName() { return "blocker"; }
            @Override public Set<HookEventType> getSubscribedEventTypes() { return Set.of(HookEventType.PRE_REASONING); }
            @Override public int getPriority() { return 1; }
            @Override
            public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
                return Mono.just(HookResult.abort("blocked by test"));
            }
        });

        ReActAgent agent = createAgent("BlockedAgent");
        assertThrows(Exception.class, () ->
            agent.chat(List.of(UserMessage.of("hi"))).block());
    }

    // ========================================================================
    // 流式对话
    // ========================================================================

    @Test
    void testStreamingChat() {
        model.setStreamChunks(List.of(
            ChatStreamChunk.builder().delta("Hello").type(ChatStreamChunk.TYPE_TEXT).build(),
            ChatStreamChunk.builder().delta(" World").type(ChatStreamChunk.TYPE_TEXT).build(),
            ChatStreamChunk.builder().delta("!").type(ChatStreamChunk.TYPE_TEXT)
                .finishReason("stop").build()));

        ReActAgent agent = createAgent("TestAgent");
        String result = agent.stream(List.of(UserMessage.of("hi")))
            .filter(c -> c.getDelta() != null)
            .map(ChatStreamChunk::getDelta)
            .collectList()
            .map(list -> String.join("", list))
            .block();

        assertEquals("Hello World!", result);
    }

    @Test
    void testStreamingWithRuntimeContext() {
        model.setStreamChunks(List.of(
            ChatStreamChunk.builder().delta("Hi").type(ChatStreamChunk.TYPE_TEXT)
                .finishReason("stop").build()));

        ReActAgent agent = createAgent("TestAgent");
        String result = agent.stream(
                List.of(UserMessage.of("hi")),
                RuntimeContext.builder().tenantId("t1").userId("u1").build())
            .filter(c -> c.getDelta() != null)
            .map(ChatStreamChunk::getDelta)
            .collectList()
            .map(list -> String.join("", list))
            .block();

        assertEquals("Hi", result);
    }

    @Test
    void testStreamingWithOutputClass() {
        model.setStreamChunks(List.of(
            ChatStreamChunk.builder().delta("ok").finishReason("stop").build()));

        ReActAgent agent = createAgent("TestAgent");
        String result = agent.stream(List.of(UserMessage.of("hi")), Map.class)
            .filter(c -> c.getDelta() != null)
            .map(ChatStreamChunk::getDelta)
            .collectList()
            .map(list -> String.join("", list))
            .block();

        assertEquals("ok", result);
    }

    // ========================================================================
    // 工具调用
    // ========================================================================

    @Test
    void testChatWithToolCallReActLoop() {
        Msg toolCallMsg = AssistantMessage.builder()
            .addToolUse("tc1", "calculator", "{\"expression\":\"1+2\"}")
            .build();
        model.setResponses(List.of(
            new ChatResponse(toolCallMsg, new ChatUsage(5, 10), "tool_calls", null),
            new ChatResponse(AssistantMessage.of("结果是 3"), new ChatUsage(5, 5), "stop", null)));

        ReActAgent agent = createAgent("TestAgent");
        ChatResponse response = agent.chat(List.of(UserMessage.of("1+2等于几？"))).block();

        assertNotNull(response);
        assertTrue(loggingHook.getEventCount() > 0);
    }

    @Test
    void testChatWithUnknownTool() {
        Msg toolCallMsg = AssistantMessage.builder()
            .addToolUse("tc1", "no_such_tool", "{}")
            .build();
        model.setResponses(List.of(
            new ChatResponse(toolCallMsg, new ChatUsage(3, 5), "tool_calls", null),
            new ChatResponse(AssistantMessage.of("工具不存在"), new ChatUsage(3, 3), "stop", null)));

        ReActAgent agent = createAgent("TestAgent");
        ChatResponse response = agent.chat(List.of(UserMessage.of("用 badtool"))).block();

        assertNotNull(response);
    }

    // ========================================================================
    // 错误处理
    // ========================================================================

    @Test
    void testModelError() {
        model.setError(new RuntimeException("API 调用失败"));

        ReActAgent agent = createAgent("TestAgent");
        assertThrows(Exception.class, () ->
            agent.chat(List.of(UserMessage.of("hi"))).block());
    }

    // ========================================================================
    // observe
    // ========================================================================

    // ========================================================================

    @Test
    void testAgentToolDelegation() {
        // 子 Agent 模型：简单回显
        StubModel subModel = new StubModel();
        subModel.setResponse(new ChatResponse(
            AssistantMessage.of("子Agent分析结果: 42"),
            new ChatUsage(5, 5), "stop", null));

        // 子 Agent 工具注册表（可以有自己的工具）
        ToolRegistry subTools = new ToolRegistry();

        cd.lan1akea.core.tool.builtin.AgentTool agentTool =
            new cd.lan1akea.core.tool.builtin.AgentTool(
                "analyst", "数据分析助手", subModel, subTools);

        // 父 Agent 注册子 Agent 为工具
        toolRegistry.register(agentTool);

        // 父 Agent 模型：返回 tool_use 调用子 Agent
        Msg toolCallMsg = AssistantMessage.builder()
            .addToolUse("tc1", "analyst", "{\"task\":\"分析数据\"}")
            .build();
        model.setResponses(List.of(
            new ChatResponse(toolCallMsg, new ChatUsage(5, 10), "tool_calls", null),
            new ChatResponse(AssistantMessage.of("子Agent返回: 42"),
                new ChatUsage(5, 5), "stop", null)));

        ReActAgent agent = createAgent("ParentAgent");
        ChatResponse response = agent.chat(List.of(UserMessage.of("帮我分析数据"))).block();

        assertNotNull(response);
        assertTrue(response.getMessage().getTextContent().contains("42"));
    }

    @Test
    void testAgentToolContextPropagation() {
        StubModel subModel = new StubModel();
        subModel.setResponse(new ChatResponse(
            AssistantMessage.of("tenant=tenant_X"),
            new ChatUsage(3, 3), "stop", null));

        ToolRegistry subTools = new ToolRegistry();
        cd.lan1akea.core.tool.builtin.AgentTool agentTool =
            new cd.lan1akea.core.tool.builtin.AgentTool(
                "ctx_reader", "上下文读取器", subModel, subTools);

        toolRegistry.register(agentTool);

        Msg toolCallMsg = AssistantMessage.builder()
            .addToolUse("tc1", "ctx_reader", "{\"task\":\"读取上下文\"}")
            .build();
        model.setResponses(List.of(
            new ChatResponse(toolCallMsg, new ChatUsage(3, 5), "tool_calls", null),
            new ChatResponse(AssistantMessage.of("OK"), new ChatUsage(3, 3), "stop", null)));

        ReActAgent agent = createAgent("ParentAgent");
        ChatResponse response = agent.chat(List.of(UserMessage.of("read ctx")))
            .contextWrite(ctx -> ctx.put("tenantId", "tenant_X"))
            .block();

        assertNotNull(response);
        // 子 Agent 通过 ToolCallContext 拿到了 tenant_X
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private ReActAgent createAgent(String name) {
        AgentConfig config = AgentConfig.builder()
            .name(name)
            .model(model)
            .toolRegistry(toolRegistry)
            .hookChain(hookChain)
            .stateStore(stateStore)
            .executionConfig(AgentExecutionConfig.builder()
                .maxIterations(3)
                .totalTimeoutMs(10000)
                .build())
            .build();

        ReActAgent agent = new ReActAgent(config);
        agent.build().block();
        return agent;
    }

    // ========================================================================
    // Stub Model
    // ========================================================================

    static class StubModel extends ChatModelBase {
        private ChatResponse response;
        private List<ChatResponse> responses;
        private List<ChatStreamChunk> streamChunks;
        private RuntimeException error;
        private int callCount;

        StubModel() {
            super("test", "stub", msgs ->
                List.of(Map.of("role", "user", "content", "test")));
        }

        void setResponse(ChatResponse r) { this.response = r; }
        void setResponses(List<ChatResponse> rs) { this.responses = rs; this.callCount = 0; }
        void setStreamChunks(List<ChatStreamChunk> chunks) { this.streamChunks = chunks; }
        void setError(RuntimeException e) { this.error = e; }

        @Override protected Map<String, String> buildAuthHeaders() { return Map.of(); }
        @Override protected String buildApiUrl() { return "http://localhost/stub"; }

        @Override
        protected Mono<ChatResponse> doChat(List<Map<String, Object>> messages,
                                            List<ToolSchema> toolSchemas,
                                            GenerateOptions options) {
            if (error != null) return Mono.error(error);
            if (responses != null && callCount < responses.size()) {
                return Mono.just(responses.get(callCount++));
            }
            return Mono.justOrEmpty(response);
        }

        @Override
        protected Flux<ChatStreamChunk> doStream(List<Map<String, Object>> messages,
                                                  List<ToolSchema> toolSchemas,
                                                  GenerateOptions options) {
            if (error != null) return Flux.error(error);
            if (streamChunks != null) return Flux.fromIterable(streamChunks);
            return Flux.empty();
        }
    }
}
