package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.CoreConstants.EventPayload;
import cd.lan1akea.core.CoreConstants.FinishReason;
import cd.lan1akea.core.CoreConstants.Intervention;
import cd.lan1akea.core.exception.HumanInterventionException;
import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.intervention.InterventionRequest;
import cd.lan1akea.core.intervention.InterventionStore;
import cd.lan1akea.core.message.*;
import cd.lan1akea.core.model.ChatStreamChunk;
import cd.lan1akea.core.model.GenerateOptions;
import cd.lan1akea.core.tool.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InterventionResolverTest {

    @Mock private InterventionStore interventionStore;
    @Mock private ToolExecutor toolExecutor;
    private ToolRegistry toolRegistry;
    private AroundHookChain aroundHooks;
    private HookDispatcher hookDispatcher;
    private ToolCallOrchestrator toolOrchestrator;
    private InterventionResolver resolver;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        toolRegistry = new ToolRegistry();
        aroundHooks = new AroundHookChain();
        hookDispatcher = new HookDispatcher(new HookChain());
        toolOrchestrator = new ToolCallOrchestrator(
                toolExecutor, toolRegistry, hookDispatcher, aroundHooks);
        resolver = new InterventionResolver(interventionStore, toolOrchestrator);
    }

    @Test
    void resolveForRecovery_pending_shouldReturnChunk() {
        InterventionRequest req = InterventionRequest.builder()
                .type(InterventionRequest.Type.TOOL_APPROVAL)
                .sessionId("s1").requestId("r1").agentName("test")
                .toolName("transfer").question("approve?").build();
        when(interventionStore.getById("int_1")).thenReturn(req);

        LoopContext ctx = buildCtx("s1");
        ctx.getInterventionState().setInterventionId("int_1");
        ctx.addMessage(buildAssistantWithToolUse("call_1", "transfer"));

        InterventionResolver.ResolvedIntervention result = resolver.resolveForRecovery(ctx);

        assertEquals(InterventionResolver.ResolvedIntervention.Action.RETURN_CHUNK, result.getAction());
        assertNotNull(result.getChunk());
        assertEquals(FinishReason.INTERRUPTED, result.getChunk().getFinishReason());
        assertTrue(result.getChunk().getDelta().contains("transfer"));
    }

    @Test
    void resolveForRecovery_approved_shouldExecuteAndContinue() {
        InterventionRequest req = InterventionRequest.builder()
                .type(InterventionRequest.Type.TOOL_APPROVAL)
                .sessionId("s1").requestId("r1").agentName("test")
                .toolName("transfer").question("approve?")
                .toolArgs(Map.of("amount", 100)).build();
        req.approve("resolver", "ok");
        when(interventionStore.getById("int_1")).thenReturn(req);
        when(toolExecutor.execute(any())).thenReturn(Mono.just(
                ToolResult.success("call_1", "done")));

        LoopContext ctx = buildCtx("s1");
        ctx.getInterventionState().setInterventionId("int_1");
        ctx.addMessage(buildAssistantWithToolUse("call_1", "transfer"));

        InterventionResolver.ResolvedIntervention result = resolver.resolveForRecovery(ctx);

        assertEquals(InterventionResolver.ResolvedIntervention.Action.EXECUTE_AND_CONTINUE, result.getAction());
        assertEquals("call_1", result.getCallId());
        assertNotNull(result.getExecution());
        ToolResult tr = result.getExecution().block();
        assertEquals("done", tr.getContent());
    }

    @Test
    void resolveForRecovery_approved_shouldFallbackToPausedToolArgs() {
        InterventionRequest req = InterventionRequest.builder()
                .type(InterventionRequest.Type.TOOL_APPROVAL)
                .sessionId("s1").requestId("r1").agentName("test")
                .toolName("transfer").question("approve?").build();
        req.approve("resolver", "ok");
        when(interventionStore.getById("int_1")).thenReturn(req);
        when(toolExecutor.execute(any())).thenReturn(Mono.just(
                ToolResult.success("call_1", "done")));

        LoopContext ctx = buildCtx("s1");
        ctx.getInterventionState().setInterventionId("int_1");
        ctx.getInterventionState().setPausedToolArgs("{\"amount\":200}");
        ctx.addMessage(buildAssistantWithToolUse("call_1", "transfer"));

        InterventionResolver.ResolvedIntervention result = resolver.resolveForRecovery(ctx);

        assertEquals(InterventionResolver.ResolvedIntervention.Action.EXECUTE_AND_CONTINUE, result.getAction());
        ToolResult tr = result.getExecution().block();
        assertEquals("done", tr.getContent());
        verify(toolExecutor).execute(argThat(
                call -> call.getArgumentsMap().get("amount").equals(200)));
    }

    @Test
    void resolveForRecovery_clarified_shouldUseModifiedArgs() {
        InterventionRequest req = InterventionRequest.builder()
                .type(InterventionRequest.Type.TOOL_CLARIFY)
                .sessionId("s1").requestId("r1").agentName("test")
                .toolName("transfer").question("clarify?").build();
        req.clarify("resolver", "ok", Map.of("amount", 300));
        when(interventionStore.getById("int_2")).thenReturn(req);
        when(toolExecutor.execute(any())).thenReturn(Mono.just(
                ToolResult.success("call_1", "done")));

        LoopContext ctx = buildCtx("s1");
        ctx.getInterventionState().setInterventionId("int_2");
        ctx.addMessage(buildAssistantWithToolUse("call_1", "transfer"));

        InterventionResolver.ResolvedIntervention result = resolver.resolveForRecovery(ctx);

        assertEquals(InterventionResolver.ResolvedIntervention.Action.EXECUTE_AND_CONTINUE, result.getAction());
        verify(toolExecutor).execute(argThat(
                call -> call.getArgumentsMap().get("amount").equals(300)));
    }

    @Test
    void resolveForRecovery_denied_shouldReturnFailureExecution() {
        InterventionRequest req = InterventionRequest.builder()
                .type(InterventionRequest.Type.TOOL_APPROVAL)
                .sessionId("s1").requestId("r1").agentName("test")
                .toolName("transfer").question("approve?").build();
        req.deny("resolver", "too risky");
        when(interventionStore.getById("int_1")).thenReturn(req);

        LoopContext ctx = buildCtx("s1");
        ctx.getInterventionState().setInterventionId("int_1");
        ctx.addMessage(buildAssistantWithToolUse("call_1", "transfer"));

        InterventionResolver.ResolvedIntervention result = resolver.resolveForRecovery(ctx);

        assertEquals(InterventionResolver.ResolvedIntervention.Action.EXECUTE_AND_CONTINUE, result.getAction());
        ToolResult tr = result.getExecution().block();
        assertFalse(tr.isSuccess());
        assertTrue(tr.getErrorMessage().contains(Intervention.MSG_DENIED));
        assertTrue(tr.getErrorMessage().contains("transfer"));
    }

    @Test
    void resolveForRecovery_expired_shouldReturnFailureExecution() {
        InterventionRequest req = InterventionRequest.builder()
                .type(InterventionRequest.Type.TOOL_APPROVAL)
                .sessionId("s1").requestId("r1").agentName("test")
                .toolName("transfer").question("approve?").build();
        req.expire();
        when(interventionStore.getById("int_1")).thenReturn(req);

        LoopContext ctx = buildCtx("s1");
        ctx.getInterventionState().setInterventionId("int_1");
        ctx.addMessage(buildAssistantWithToolUse("call_1", "transfer"));

        InterventionResolver.ResolvedIntervention result = resolver.resolveForRecovery(ctx);

        assertEquals(InterventionResolver.ResolvedIntervention.Action.EXECUTE_AND_CONTINUE, result.getAction());
        ToolResult tr = result.getExecution().block();
        assertFalse(tr.isSuccess());
        assertTrue(tr.getErrorMessage().contains(Intervention.MSG_EXPIRED));
    }

    @Test
    void resolveForRecovery_nullRequest_shouldReEnterAndClearState() {
        when(interventionStore.getById("int_1")).thenReturn(null);

        LoopContext ctx = buildCtx("s1");
        ctx.getInterventionState().setInterventionId("int_1");

        InterventionResolver.ResolvedIntervention result = resolver.resolveForRecovery(ctx);

        assertEquals(InterventionResolver.ResolvedIntervention.Action.RE_ENTER, result.getAction());
        assertNull(ctx.getInterventionState().getInterventionId());
        assertFalse(ctx.getInterventionState().hasPending());
    }

    @Test
    void resolveForRecovery_nullCallId_shouldReEnterAndClear() {
        InterventionRequest req = InterventionRequest.builder()
                .type(InterventionRequest.Type.TOOL_APPROVAL)
                .sessionId("s1").requestId("r1").agentName("test")
                .toolName("transfer").question("approve?").build();
        req.approve("resolver", "ok");
        when(interventionStore.getById("int_1")).thenReturn(req);

        LoopContext ctx = buildCtx("s1");
        ctx.getInterventionState().setInterventionId("int_1");
        // No assistant message added — findToolCallId will return null

        InterventionResolver.ResolvedIntervention result = resolver.resolveForRecovery(ctx);

        assertEquals(InterventionResolver.ResolvedIntervention.Action.RE_ENTER, result.getAction());
        assertNull(ctx.getInterventionState().getInterventionId());
    }

    @Test
    void resolveForRecovery_noInterventionId_shouldReEnter() {
        LoopContext ctx = buildCtx("s1");

        InterventionResolver.ResolvedIntervention result = resolver.resolveForRecovery(ctx);

        assertEquals(InterventionResolver.ResolvedIntervention.Action.RE_ENTER, result.getAction());
    }

    @Test
    void createIntervention_shouldBuildRequestAndSetCtxState() {
        when(interventionStore.create(any())).thenReturn("int_new");
        LoopContext ctx = buildCtx("s1");
        HumanInterventionException hie = HumanInterventionException.approval(
                "transfer", "confirm transfer?",
                ToolCallContext.of("call_1", "transfer", Map.of("amount", 100)));

        ChatStreamChunk chunk = resolver.createIntervention(hie, ctx);

        assertEquals(Intervention.CHUNK_TYPE, chunk.getType());
        assertEquals(Intervention.FINISH_REASON, chunk.getFinishReason());
        assertTrue(chunk.getDelta().contains("int_new"));
        assertTrue(chunk.getDelta().contains("confirm transfer?"));

        assertEquals("int_new", ctx.getInterventionState().getInterventionId());
        assertEquals("TOOL_APPROVAL", ctx.getInterventionState().getInterventionType());
        assertNotNull(ctx.getInterventionState().getPausedToolArgs());
        assertTrue(ctx.isInterrupted());

        verify(interventionStore).create(any());
    }

    @Test
    void createIntervention_clarify_shouldSetCorrectType() {
        when(interventionStore.create(any())).thenReturn("int_clarify");
        LoopContext ctx = buildCtx("s1");
        HumanInterventionException hie = HumanInterventionException.clarify(
                "transfer", "what amount?",
                ToolCallContext.of("call_1", "transfer", Map.of("amount", 0)));

        ChatStreamChunk chunk = resolver.createIntervention(hie, ctx);

        assertTrue(chunk.getDelta().contains("int_clarify"));
        assertEquals("TOOL_CLARIFY", ctx.getInterventionState().getInterventionType());
    }

    @Test
    void findToolCallId_shouldMatchByName() {
        LoopContext ctx = buildCtx("s1");
        Msg assistant = Msg.builder(MsgRole.ASSISTANT)
                .addToolUse("call_a", "search", "{}")
                .addToolUse("call_b", "transfer", "{}")
                .build();
        ctx.addMessage(assistant);

        String callId = resolver.findToolCallId(ctx, "transfer");
        assertEquals("call_b", callId);
    }

    @Test
    void findToolCallId_noMatch_shouldFallbackToLast() {
        LoopContext ctx = buildCtx("s1");
        Msg assistant = Msg.builder(MsgRole.ASSISTANT)
                .addToolUse("call_a", "search", "{}")
                .addToolUse("call_b", "transfer", "{}")
                .build();
        ctx.addMessage(assistant);

        String callId = resolver.findToolCallId(ctx, "unknown_tool");
        assertEquals("call_b", callId);
    }

    @Test
    void findToolCallId_noAssistantMessage_shouldReturnNull() {
        LoopContext ctx = buildCtx("s1");
        ctx.addMessage(UserMessage.of("hi"));

        String callId = resolver.findToolCallId(ctx, "transfer");
        assertNull(callId);
    }

    @Test
    void findToolCallId_emptyToolBlocks_shouldReturnNull() {
        LoopContext ctx = buildCtx("s1");
        ctx.addMessage(AssistantMessage.of("no tools"));

        String callId = resolver.findToolCallId(ctx, "transfer");
        assertNull(callId);
    }

    @Test
    void buildSignalChunk_shouldContainAllFields() {
        ChatStreamChunk chunk = resolver.buildSignalChunk(
                "int_1", "question text", "TOOL_APPROVAL", "transfer");

        assertEquals(Intervention.CHUNK_TYPE, chunk.getType());
        assertEquals(Intervention.FINISH_REASON, chunk.getFinishReason());
        assertTrue(chunk.getDelta().contains("int_1"));
        assertTrue(chunk.getDelta().contains("question text"));
        assertTrue(chunk.getDelta().contains("TOOL_APPROVAL"));
        assertTrue(chunk.getDelta().contains("transfer"));
        assertTrue(chunk.getDelta().contains(EventPayload.TYPE));
        assertTrue(chunk.getDelta().contains(Intervention.PAYLOAD_TYPE));
    }

    private LoopContext buildCtx(String sessionId) {
        return LoopContext.builder()
                .agentName("test").sessionId(sessionId)
                .tenantId("t1").userId("u1")
                .messages(new ArrayList<>(List.of(UserMessage.of("hi"))))
                .generateOptions(GenerateOptions.defaults())
                .stream(true).build();
    }

    private Msg buildAssistantWithToolUse(String callId, String toolName) {
        return Msg.builder(MsgRole.ASSISTANT)
                .addToolUse(callId, toolName, "{}")
                .build();
    }
}
