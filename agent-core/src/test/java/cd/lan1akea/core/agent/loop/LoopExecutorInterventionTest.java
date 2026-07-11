package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.CoreConstants.FinishReason;
import cd.lan1akea.core.exception.HumanInterventionException;
import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.intervention.InterventionRequest;
import cd.lan1akea.core.intervention.InterventionStore;
import cd.lan1akea.core.message.MsgRole;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LoopExecutorInterventionTest {

    @Mock private ChatModel model;
    @Mock private ToolExecutor toolExecutor;
    @Mock private InterventionStore interventionStore;

    private HookDispatcher hookDispatcher;
    private ToolRegistry toolRegistry;
    private LoopExecutor executor;
    private ToolCallContext capturedCallParam;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        toolRegistry = new ToolRegistry();
        AroundHookChain aroundHooks = new AroundHookChain();
        hookDispatcher = spy(new HookDispatcher(new HookChain()));
        doReturn(Mono.just(HookResult.continue_())).when(hookDispatcher).dispatch(any(), any());

        ModelCallPipeline modelPipeline = new ModelCallPipeline(
                model, hookDispatcher, toolRegistry, aroundHooks, AgentMetrics.NOOP);
        ToolCallOrchestrator orchestrator = new ToolCallOrchestrator(
                toolExecutor, toolRegistry, hookDispatcher, aroundHooks);

        InterventionResolver interventionResolver =
                new InterventionResolver(interventionStore, orchestrator);
        executor = new LoopExecutor(modelPipeline, orchestrator, hookDispatcher,
                AgentMetrics.NOOP, new Cl100kTokenEstimator(), interventionResolver);
    }

    // ===========================================================
    // TOOL_APPROVAL: 工具抛审批 → 暂停 → 返回 intervention_required
    // ===========================================================

    @Test
    void toolApproval_shouldPauseAndEmitInterventionChunk() {
        when(model.streamWithTools(any(), any(), any()))
                .thenReturn(Flux.just(
                        ChatStreamChunk.builder().type(ChatStreamChunk.TYPE_TOOL_USE_START)
                                .toolUseId("c1").toolName("transfer").build(),
                        ChatStreamChunk.builder().type(ChatStreamChunk.TYPE_TOOL_USE_DELTA)
                                .toolUseId("c1").delta("{}").build(),
                        ChatStreamChunk.builder().finishReason("tool_calls").build()));

        when(toolExecutor.execute(any()))
                .thenReturn(Mono.error(HumanInterventionException.approval(
                        "transfer", "确认转账?", ToolCallContext.of("c1", "transfer", Map.of()))));

        when(interventionStore.create(any())).thenReturn("int_001");

        LoopContext ctx = buildCtx("s1");

        StepVerifier.create(executor.runStream(ctx).collectList())
                .assertNext(chunks -> {
                    assertFalse(chunks.isEmpty(), "should have chunks");
                    // Should contain the intervention signal
                    boolean hasIntervention = chunks.stream()
                            .anyMatch(c -> "intervention".equals(c.getType())
                                    && c.getDelta() != null
                                    && c.getDelta().contains("int_001"));
                    assertTrue(hasIntervention, "should contain intervention chunk: " + chunks);
                })
                .verifyComplete();

        // 验证介入记录已创建
        verify(interventionStore, times(1)).create(any());
        // 验证 ctx 已暂停
        assertTrue(ctx.isInterrupted());
        assertEquals("int_001", ctx.getInterventionState().getInterventionId());
    }

    // ===========================================================
    // 一般错误：工具抛普通异常 → 作为 failure 返回，继续循环
    // ===========================================================

    @Test
    void normalToolError_shouldReturnFailureAndContinue() {
        when(model.streamWithTools(any(), any(), any()))
                .thenReturn(Flux.just(
                        chunkToolStart("c1", "bad"),
                        chunkFinish("tool_calls")))
                .thenReturn(Flux.just(
                        chunkText("recovered"), chunkFinish(FinishReason.STOP)));

        // 第一次抛普通异常
        when(toolExecutor.execute(any()))
                .thenReturn(Mono.error(new RuntimeException("工具崩溃")));

        LoopContext ctx = buildCtx("s3");

        StepVerifier.create(executor.runStream(ctx).collectList())
                .assertNext(chunks -> {
                    boolean hasError = chunks.stream()
                            .anyMatch(c -> c.getDelta() != null && c.getDelta().contains("工具崩溃"));
                    boolean hasRecovery = chunks.stream()
                            .anyMatch(c -> "recovered".equals(c.getDelta()));
                    assertTrue(hasError, "should contain tool error chunk");
                    assertTrue(hasRecovery, "should recover and get model response");
                })
                .verifyComplete();

        // 不创建介入记录
        verify(interventionStore, never()).create(any());
    }

    // ===========================================================
    // 介入恢复：approved 路径 → 使用 ctx.pausedToolArgs
    // ===========================================================

    @Test
    void resumeApproved_shouldUsePausedToolArgs() {
        InterventionRequest req = InterventionRequest.builder()
                .type(InterventionRequest.Type.TOOL_APPROVAL)
                .sessionId("s1").requestId("r1").agentName("test")
                .toolName("transfer").question("approve?").build();
        req.approve("resolver", "ok");

        when(interventionStore.getById("int_1")).thenReturn(req);
        when(toolExecutor.execute(any())).thenReturn(Mono.just(
                ToolResult.success("resume_int_1", "done")));
        when(model.streamWithTools(any(), any(), any()))
                .thenReturn(Flux.just(ChatStreamChunk.of("ok", FinishReason.STOP)));

        LoopContext ctx = buildCtx("s1");
        ctx.getInterventionState().setInterventionId("int_1");
        ctx.getInterventionState().setInterventionType("TOOL_APPROVAL");
        ctx.getInterventionState().setPausedToolArgs("{\"amount\":100}");
        addAssistantWithToolUse(ctx, "resume_int_1", "transfer");

        StepVerifier.create(executor.runStream(ctx).collectList())
                .assertNext(chunks -> {
                    boolean hasResult = chunks.stream()
                            .anyMatch(c -> "done".equals(c.getDelta()));
                    assertTrue(hasResult, "should contain tool result: " + chunks);
                })
                .verifyComplete();

        verify(toolExecutor, times(1)).execute(argThat(
                call -> call.getArgumentsMap().get("amount").equals(100)));
    }

    // ===========================================================
    // 介入恢复：clarified 路径 → 使用 req.modifiedArgs
    // ===========================================================

    @Test
    void resumeClarified_shouldUseModifiedArgs() {
        InterventionRequest req = InterventionRequest.builder()
                .type(InterventionRequest.Type.TOOL_CLARIFY)
                .sessionId("s1").requestId("r1").agentName("test")
                .toolName("transfer").question("clarify?").build();
        req.clarify("resolver", "ok", Map.of("amount", 200));

        when(interventionStore.getById("int_2")).thenReturn(req);
        when(toolExecutor.execute(any())).thenReturn(Mono.just(
                ToolResult.success("resume_int_2", "done")));
        when(model.streamWithTools(any(), any(), any()))
                .thenReturn(Flux.just(ChatStreamChunk.of("ok", FinishReason.STOP)));

        LoopContext ctx = buildCtx("s1");
        ctx.getInterventionState().setInterventionId("int_2");
        ctx.getInterventionState().setInterventionType("TOOL_CLARIFY");
        ctx.getInterventionState().setPausedToolArgs("{\"amount\":100}");
        addAssistantWithToolUse(ctx, "resume_int_2", "transfer");

        StepVerifier.create(executor.runStream(ctx).collectList())
                .assertNext(chunks -> {
                    boolean hasResult = chunks.stream()
                            .anyMatch(c -> "done".equals(c.getDelta()));
                    assertTrue(hasResult, "should contain tool result: " + chunks);
                })
                .verifyComplete();

        verify(toolExecutor, times(1)).execute(argThat(
                call -> call.getArgumentsMap().get("amount").equals(200)));
    }


    // ===========================================================
    // Observe 闭环：介入恢复后走 Observe 持久化
    // ===========================================================

    @Test
    void resumeApproved_shouldGoThroughObserve() {
        InterventionRequest req = InterventionRequest.builder()
                .type(InterventionRequest.Type.TOOL_APPROVAL)
                .sessionId("s1").requestId("r1").agentName("test")
                .toolName("transfer").question("approve?").build();
        req.approve("resolver", "ok");

        when(interventionStore.getById("int_1")).thenReturn(req);
        when(toolExecutor.execute(any())).thenReturn(Mono.just(
                ToolResult.success("resume_int_1", "done")));
        when(model.streamWithTools(any(), any(), any()))
                .thenReturn(Flux.just(ChatStreamChunk.of("ok", FinishReason.STOP)));

        LoopContext ctx = buildCtx("s1");
        ctx.getInterventionState().setInterventionId("int_1");
        ctx.getInterventionState().setInterventionType("TOOL_APPROVAL");
        ctx.getInterventionState().setPausedToolArgs("{\"amount\":100}");
        addAssistantWithToolUse(ctx, "resume_int_1", "transfer");

        executor.runStream(ctx).collectList().block();

        // AFTER_ITERATION 被触发（介入恢复后走 Observe）
        verify(hookDispatcher, atLeastOnce()).dispatch(
                argThat(e -> e.getHookEventType() == HookEventType.AFTER_ITERATION),
                any());
        assertEquals(2, ctx.getIteration());
    }

    // ===========================================================
    // helpers
    // ===========================================================

    private void addAssistantWithToolUse(LoopContext ctx, String callId, String toolName) {
        ctx.addMessage(Msg.builder(MsgRole.ASSISTANT)
                .addToolUse(callId, toolName, "{}")
                .build());
    }

    private LoopContext buildCtx(String sessionId) {
        return LoopContext.builder()
                .agentName("test").sessionId(sessionId)
                .messages(List.of(UserMessage.of("hi")))
                .generateOptions(GenerateOptions.defaults())
                .stream(true).build();
    }

    private ChatStreamChunk chunkToolStart(String id, String name) {
        return ChatStreamChunk.builder().type(ChatStreamChunk.TYPE_TOOL_USE_START)
                .toolUseId(id).toolName(name).build();
    }

    private ChatStreamChunk chunkFinish(String reason) {
        return ChatStreamChunk.builder().finishReason(reason).build();
    }

    private ChatStreamChunk chunkText(String text) {
        return ChatStreamChunk.builder().delta(text).type(ChatStreamChunk.TYPE_TEXT).build();
    }
}
