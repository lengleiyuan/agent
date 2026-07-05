package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.CoreConstants.FinishReason;
import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.message.*;
import cd.lan1akea.core.metrics.AgentMetrics;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.tool.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LoopExecutorTest {

    @Mock private ChatModel model;
    @Mock private ToolExecutor toolExecutor;
    @Mock private HookDispatcher hookDispatcher;
    @Mock private HookChain hookChain;

    private ToolRegistry toolRegistry;
    private LoopExecutor executor;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(hookDispatcher.dispatch(any(), any())).thenReturn(Mono.just(HookResult.continue_()));

        toolRegistry = new ToolRegistry();
        AroundHookChain aroundHooks = new AroundHookChain();

        LoopDecisionEngine engine = new LoopDecisionEngine();
        ModelCallPipeline modelPipeline = new ModelCallPipeline(
                model, hookDispatcher, toolRegistry, aroundHooks, AgentMetrics.NOOP);
        ToolCallOrchestrator orchestrator = new ToolCallOrchestrator(
                toolExecutor, toolRegistry, hookDispatcher, aroundHooks);

        executor = new LoopExecutor(engine, modelPipeline, orchestrator, hookDispatcher,
                AgentMetrics.NOOP, new cd.lan1akea.core.intervention.InMemoryInterventionStore());
    }

    // ============================================================
    // 非流式 run() — 核心：应该返回最终模型响应，不含工具结果
    // ============================================================

    @Test
    void run_textOnly_shouldReturnChatResponse() {
        Msg responseMsg = new AssistantMessage(
                List.of(new TextBlock("hello")), null);
        ChatResponse modelResp = new ChatResponse(responseMsg, new ChatUsage(10, 5), FinishReason.STOP, "test");

        when(model.streamWithTools(any(), any(), any()))
                .thenReturn(Flux.just(
                        ChatStreamChunk.builder().delta("hello").finishReason(FinishReason.STOP).build()));

        LoopContext ctx = LoopContext.builder()
                .agentName("test").messages(List.of(UserMessage.of("hi")))
                .generateOptions(GenerateOptions.defaults()).stream(false).build();

        StepVerifier.create(executor.run(ctx))
                .assertNext(resp -> {
                    assertNotNull(resp);
                    assertEquals(FinishReason.STOP, resp.getFinishReason());
                })
                .verifyComplete();

        // ctx should have the last response set
        assertNotNull(ctx.getLastResponse());
        assertEquals(FinishReason.STOP, ctx.getLastResponse().getFinishReason());
    }

    // ============================================================
    // 流式 runStream() — 正常对话无工具
    // ============================================================

    @Test
    void runStream_textOnly_shouldEmitChunks() {
        when(model.streamWithTools(any(), any(), any()))
                .thenReturn(Flux.just(
                        ChatStreamChunk.builder().delta("hello").finishReason(FinishReason.STOP).build()));

        LoopContext ctx = LoopContext.builder()
                .agentName("test").messages(List.of(UserMessage.of("hi")))
                .generateOptions(GenerateOptions.defaults()).stream(true).build();

        StepVerifier.create(executor.runStream(ctx))
                .expectNextMatches(c -> ChatStreamChunk.TYPE_TEXT.equals(c.getType())
                        && "hello".equals(c.getDelta()))
                .verifyComplete();
    }

    // ============================================================
    // 流式 — 有工具调用
    // ============================================================

    @Test
    void runStream_withTools_shouldExecuteAndContinue() {
        when(model.streamWithTools(any(), any(), any()))
                .thenReturn(
                        Flux.just(
                                // First call: tool_use_start + tool_use_delta with args
                                ChatStreamChunk.builder()
                                        .type(ChatStreamChunk.TYPE_TOOL_USE_START)
                                        .toolUseId("call_1").toolName("greet").build(),
                                ChatStreamChunk.builder()
                                        .type(ChatStreamChunk.TYPE_TOOL_USE_DELTA)
                                        .toolUseId("call_1").delta("{}").build(),
                                ChatStreamChunk.builder()
                                        .finishReason("tool_calls").build()))
                .thenReturn(
                        // Second call: text response after tool results
                        Flux.just(ChatStreamChunk.builder().delta("done")
                                .finishReason(FinishReason.STOP).build()));

        when(toolExecutor.execute(any())).thenReturn(Mono.just(ToolResult.success("call_1", "ok")));

        LoopContext ctx = LoopContext.builder()
                .agentName("test").messages(List.of(UserMessage.of("hi")))
                .generateOptions(GenerateOptions.defaults()).stream(true).build();

        // Just verify completion - don't assert exact chunk order
        StepVerifier.create(executor.runStream(ctx).collectList())
                .assertNext(chunks -> {
                    assertFalse(chunks.isEmpty(), "should emit chunks");

                    // Verify "done" text from second model call is present
                    boolean hasDone = chunks.stream()
                            .anyMatch(c -> "done".equals(c.getDelta()));
                    assertTrue(hasDone, "should contain second model call response");

                    // Verify tool result is present
                    boolean hasOk = chunks.stream()
                            .anyMatch(c -> "ok".equals(c.getDelta()));
                    assertTrue(hasOk, "should contain tool result");
                })
                .verifyComplete();

        verify(toolExecutor, times(1)).execute(any());
        verify(model, times(2)).streamWithTools(any(), any(), any());
    }

    // ============================================================
    // 中断
    // ============================================================

    @Test
    void runStream_interrupted_shouldStop() {
        LoopContext ctx = LoopContext.builder()
                .agentName("test").messages(List.of(UserMessage.of("hi")))
                .generateOptions(GenerateOptions.defaults()).stream(true).build();
        ctx.interrupt();

        StepVerifier.create(executor.runStream(ctx))
                .expectNextMatches(c -> FinishReason.INTERRUPTED.equals(c.getFinishReason()))
                .verifyComplete();

        verify(model, never()).streamWithTools(any(), any(), any());
    }

    // ============================================================
    // PRE_SUMMARIZE：达到最大迭代 → Hook 可覆盖内置兜底
    // ============================================================

    @Test
    void maxIterations_shouldDispatchPreSummarizeHook() {
        when(model.streamWithTools(any(), any(), any()))
                .thenReturn(Flux.just(ChatStreamChunk.builder().delta("summary").finishReason(FinishReason.STOP).build()));

        LoopContext ctx = LoopContext.builder()
                .agentName("test").messages(List.of(UserMessage.of("hi")))
                .generateOptions(GenerateOptions.defaults()).maxIterations(1).stream(true).build();
        ctx.setIteration(1); // reached max

        StepVerifier.create(executor.runStream(ctx))
                .expectNextMatches(c -> "summary".equals(c.getDelta()))
                .verifyComplete();

        // Verify PRE_SUMMARIZE hook was dispatched
        verify(hookDispatcher, atLeastOnce()).dispatch(
                argThat(e -> e.getHookEventType() == HookEventType.PRE_SUMMARIZE),
                any());
        // Tools should be disabled after fallback
        assertEquals(ToolChoicePolicy.NONE, ctx.getGenerateOptions().getToolChoice());
    }

    @Test
    void maxIterations_bypassMessage_shouldSkipModel() {
        // Hook sets bypassMessage → model never called
        when(hookDispatcher.dispatch(any(HookEvent.class), any()))
                .thenAnswer(inv -> {
                    HookEvent event = inv.getArgument(0);
                    if (event.getHookEventType() == HookEventType.PRE_SUMMARIZE) {
                        if (event instanceof ReasoningEvent) {
                            ((ReasoningEvent) event).setBypassMessage(
                                    Msg.builder(MsgRole.ASSISTANT).addText("自定义摘要").build());
                        }
                    }
                    return Mono.just(HookResult.continue_());
                });

        LoopContext ctx = LoopContext.builder()
                .agentName("test").messages(List.of(UserMessage.of("hi")))
                .generateOptions(GenerateOptions.defaults()).maxIterations(1).stream(true).build();
        ctx.setIteration(1);

        StepVerifier.create(executor.runStream(ctx))
                .expectNextMatches(c -> "自定义摘要".equals(c.getDelta()))
                .verifyComplete();

        verify(model, never()).streamWithTools(any(), any(), any());
    }

    @Test
    void maxIterations_fallback_shouldDisableTools() {
        when(model.streamWithTools(any(), any(), any()))
                .thenReturn(Flux.just(ChatStreamChunk.builder().delta("done").finishReason(FinishReason.STOP).build()));

        LoopContext ctx = LoopContext.builder()
                .agentName("test").messages(List.of(UserMessage.of("hi")))
                .generateOptions(GenerateOptions.builder().toolChoice(ToolChoicePolicy.AUTO).build())
                .maxIterations(1).stream(true).build();
        ctx.setIteration(1);

        executor.runStream(ctx).collectList().block();

        assertEquals(ToolChoicePolicy.NONE, ctx.getGenerateOptions().getToolChoice());
    }

    // ============================================================
    // ReasoningEvent 携带消息
    // ============================================================

    @Test
    void preReasoningHook_shouldSeeMessages() {
        List<Msg> messages = List.of(UserMessage.of("hi"));
        when(model.streamWithTools(any(), any(), any()))
                .thenReturn(Flux.just(ChatStreamChunk.builder().delta("ok").finishReason(FinishReason.STOP).build()));

        LoopContext ctx = LoopContext.builder()
                .agentName("test").messages(messages)
                .generateOptions(GenerateOptions.defaults()).stream(true).build();

        executor.runStream(ctx).collectList().block();

        verify(hookDispatcher, atLeastOnce()).dispatch(
                argThat(e -> e.getHookEventType() == HookEventType.PRE_REASONING
                        && e instanceof ReasoningEvent
                        && !((ReasoningEvent) e).getMessages().isEmpty()),
                any());
    }

    // ============================================================
    // LoopContext.setGenerateOptions
    // ============================================================

    @Test
    void setGenerateOptions_shouldOverrideOptions() {
        LoopContext ctx = LoopContext.builder()
                .agentName("test").messages(List.of())
                .generateOptions(GenerateOptions.builder().maxTokens(100).build()).build();

        assertEquals(Integer.valueOf(100), ctx.getGenerateOptions().getMaxTokens());

        ctx.setGenerateOptions(GenerateOptions.builder().maxTokens(200).toolChoice(ToolChoicePolicy.NONE).build());

        assertEquals(Integer.valueOf(200), ctx.getGenerateOptions().getMaxTokens());
        assertEquals(ToolChoicePolicy.NONE, ctx.getGenerateOptions().getToolChoice());
    }
}
