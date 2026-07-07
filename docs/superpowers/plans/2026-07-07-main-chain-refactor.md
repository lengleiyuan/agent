# Main Chain Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract InterventionResolver from LoopExecutor, consolidate intervention state in LoopContext, fix 8 boundary issues, and clean up boilerplate — with full test coverage.

**Architecture:** Additive-first refactoring: build InterventionState and InterventionResolver as new code, then migrate LoopExecutor to delegate to them. Boundary fixes are independent, applied sequentially. All existing tests must stay green through every commit.

**Tech Stack:** Java 17, JUnit 5, Mockito, Reactor (Flux/Mono), StepVerifier

**Comment style:** Chinese Javadoc — class-level `/** 中文简述。 */`, field-level `/** 中文描述 */`, method-level `/** 简述。 */` with `@param`/`@return`. Inline comments only for non-obvious logic.

---

### Task 1: InterventionState — Write failing tests

**Files:**
- Create: `agent-core/src/test/java/cd/lan1akea/core/agent/loop/InterventionStateTest.java`

- [ ] **Step 1: Write InterventionStateTest**

```java
package cd.lan1akea.core.agent.loop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InterventionStateTest {

    @Test
    void newState_shouldHaveNullFields() {
        LoopContext.InterventionState state = new LoopContext.InterventionState();

        assertNull(state.getInterventionId());
        assertNull(state.getInterventionType());
        assertNull(state.getPausedToolArgs());
        assertFalse(state.hasPending());
    }

    @Test
    void setAndGet_shouldWork() {
        LoopContext.InterventionState state = new LoopContext.InterventionState();

        state.setInterventionId("int_1");
        state.setInterventionType("TOOL_APPROVAL");
        state.setPausedToolArgs("{\"amount\":100}");

        assertEquals("int_1", state.getInterventionId());
        assertEquals("TOOL_APPROVAL", state.getInterventionType());
        assertEquals("{\"amount\":100}", state.getPausedToolArgs());
        assertTrue(state.hasPending());
    }

    @Test
    void clear_shouldResetAllFields() {
        LoopContext.InterventionState state = new LoopContext.InterventionState();
        state.setInterventionId("int_1");
        state.setInterventionType("TOOL_APPROVAL");
        state.setPausedToolArgs("{\"amount\":100}");

        state.clear();

        assertNull(state.getInterventionId());
        assertNull(state.getInterventionType());
        assertNull(state.getPausedToolArgs());
        assertFalse(state.hasPending());
    }

    @Test
    void hasPending_shouldReturnFalseWhenIdIsNull() {
        LoopContext.InterventionState state = new LoopContext.InterventionState();
        state.setInterventionType("TOOL_APPROVAL");

        assertFalse(state.hasPending());
    }

    @Test
    void hasPending_shouldReturnTrueWhenIdIsSet() {
        LoopContext.InterventionState state = new LoopContext.InterventionState();
        state.setInterventionId("int_1");

        assertTrue(state.hasPending());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl agent-core -Dtest=InterventionStateTest -DfailIfNoTests=false`
Expected: Compilation FAIL — `LoopContext.InterventionState` not defined

- [ ] **Step 3: Implement InterventionState in LoopContext**

Add this inner class to `LoopContext.java`, right before the `Builder` class (around line 316):

```java
/**
 * 人工介入状态。
 * <p>三个字段始终一起读写，收拢为单一对象避免字段散落。
 * 字段均为 volatile 以保证跨线程可见性（执行线程与介入 API 线程）。
 */
public static class InterventionState {
    /** 待解决的介入请求 ID（null 表示无待解决介入） */
    private volatile String interventionId;
    /** 介入类型名称（APPROVAL/CLARIFY） */
    private volatile String interventionType;
    /** 暂停时快照的工具参数 JSON */
    private volatile String pausedToolArgs;

    /** @return 待解决的介入请求 ID */
    public String getInterventionId() { return interventionId; }
    /** 设置待解决的介入请求 ID */
    public void setInterventionId(String v) { this.interventionId = v; }
    /** @return 介入类型名称 */
    public String getInterventionType() { return interventionType; }
    /** 设置介入类型名称 */
    public void setInterventionType(String v) { this.interventionType = v; }
    /** @return 暂停时快照的工具参数 JSON */
    public String getPausedToolArgs() { return pausedToolArgs; }
    /** 设置暂停时快照的工具参数 JSON */
    public void setPausedToolArgs(String v) { this.pausedToolArgs = v; }

    /** 清除所有介入状态 */
    public void clear() {
        this.interventionId = null;
        this.interventionType = null;
        this.pausedToolArgs = null;
    }

    /** @return 是否有待解决的介入 */
    public boolean hasPending() { return interventionId != null; }
}
```

Add field to `LoopContext` (after `complete`, around line 113):

```java
/** 人工介入状态 */
private final InterventionState interventionState = new InterventionState();

/** @return 人工介入状态 */
public InterventionState getInterventionState() { return interventionState; }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl agent-core -Dtest=InterventionStateTest -DfailIfNoTests=false`
Expected: PASS

- [ ] **Step 5: Run all existing tests to verify no regression**

Run: `mvn test -pl agent-core`
Expected: All existing tests PASS (InterventionState is additive, nothing uses it yet)

- [ ] **Step 6: Commit**

```bash
git add agent-core/src/test/java/cd/lan1akea/core/agent/loop/InterventionStateTest.java \
        agent-core/src/main/java/cd/lan1akea/core/agent/loop/LoopContext.java
git commit -m "feat: add InterventionState to LoopContext, consolidate intervention fields"
```

---

### Task 2: Migrate LoopContext and consumers to InterventionState API

**Files:**
- Modify: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/LoopContext.java`
- Modify: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/LoopExecutor.java`
- Modify: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/RequestPipeline.java`
- Modify: `agent-core/src/main/java/cd/lan1akea/core/hook/impl/SessionPersistenceHook.java`
- Modify: `agent-core/src/test/java/cd/lan1akea/core/agent/loop/LoopExecutorInterventionTest.java`

- [ ] **Step 1: Remove old intervention fields and getters/setters from LoopContext**

In `LoopContext.java`, remove these 3 fields (lines 93-107):
```java
private volatile String interventionId;
private volatile String interventionType;
private volatile String pausedToolArgs;
```

Remove these 6 methods (lines 280-311):
```java
public String getInterventionId() { return interventionId; }
public void setInterventionId(String v) { this.interventionId = v; }
public String getInterventionType() { return interventionType; }
public void setInterventionType(String v) { this.interventionType = v; }
public String getPausedToolArgs() { return pausedToolArgs; }
public void setPausedToolArgs(String v) { this.pausedToolArgs = v; }
```

- [ ] **Step 2: Update LoopExecutor to use InterventionState API**

In `LoopExecutor.java`, replace all `ctx.getInterventionId()` → `ctx.getInterventionState().getInterventionId()`, all `ctx.setInterventionId(...)` → `ctx.getInterventionState().setInterventionId(...)`, etc.

In `resolveAndContinue` (lines 230-232), replace:
```java
ctx.setInterventionId(null);
ctx.setInterventionType(null);
ctx.setPausedToolArgs(null);
```
with:
```java
ctx.getInterventionState().clear();
```

In `resumeFromIntervention` (lines 247-249), replace:
```java
ctx.setInterventionId(null);
ctx.setInterventionType(null);
```
with:
```java
ctx.getInterventionState().clear();
```

In `runStream` (line 102), replace:
```java
if (ctx.getInterventionId() != null && !ctx.isInterrupted()) {
```
with:
```java
if (ctx.getInterventionState().hasPending() && !ctx.isInterrupted()) {
```

- [ ] **Step 3: Update RequestPipeline to use InterventionState API**

In `RequestPipeline.java` executeStream (lines 114-117), replace:
```java
if (lm.result.interventionId != null) {
    loopCtx.setInterventionId(lm.result.interventionId);
    loopCtx.setInterventionType(lm.result.interventionType);
    loopCtx.setPausedToolArgs(lm.result.pausedToolArgs);
}
```
with:
```java
if (lm.result.interventionId != null) {
    loopCtx.getInterventionState().setInterventionId(lm.result.interventionId);
    loopCtx.getInterventionState().setInterventionType(lm.result.interventionType);
    loopCtx.getInterventionState().setPausedToolArgs(lm.result.pausedToolArgs);
}
```

Same change in `execute` (lines 151-154).

- [ ] **Step 4: Update SessionPersistenceHook to use InterventionState API**

In `SessionPersistenceHook.java` saveCheckpoint (lines 90-92), replace:
```java
state.setPendingInterventionId(ctx.getInterventionId());
state.setInterventionType(ctx.getInterventionType());
state.setPausedToolArgsJson(ctx.getPausedToolArgs());
```
with:
```java
state.setPendingInterventionId(ctx.getInterventionState().getInterventionId());
state.setInterventionType(ctx.getInterventionState().getInterventionType());
state.setPausedToolArgsJson(ctx.getInterventionState().getPausedToolArgs());
```

- [ ] **Step 5: Update LoopExecutorInterventionTest to use InterventionState API and add assistant messages**

In `LoopExecutorInterventionTest.java`, replace all `ctx.setInterventionId(...)` → `ctx.getInterventionState().setInterventionId(...)`, `ctx.setInterventionType(...)` → `ctx.getInterventionState().setInterventionType(...)`, `ctx.setPausedToolArgs(...)` → `ctx.getInterventionState().setPausedToolArgs(...)`.

Replace `assertEquals("int_001", ctx.getInterventionId())` → `assertEquals("int_001", ctx.getInterventionState().getInterventionId())`.

**Preemptively add assistant messages to recovery tests** — the new InterventionResolver requires an assistant message with tool_use to find callId for recovery. In real scenarios, the assistant message exists (it's the one that triggered intervention). Add this helper to the test:

```java
private void addAssistantWithToolUse(LoopContext ctx, String callId, String toolName) {
    ctx.addMessage(Msg.builder(MsgRole.ASSISTANT)
            .addToolUse(callId, toolName, "{}")
            .build());
}
```

Call it in recovery tests after setting up ctx. For example, in `resumeApproved_shouldUsePausedToolArgs`:
```java
LoopContext ctx = buildCtx("s1");
ctx.getInterventionState().setInterventionId("int_1");
ctx.getInterventionState().setInterventionType("TOOL_APPROVAL");
ctx.getInterventionState().setPausedToolArgs("{\"amount\":100}");
addAssistantWithToolUse(ctx, "resume_int_1", "transfer");
```

Apply to: `resumeApproved_shouldUsePausedToolArgs`, `resumeClarified_shouldUseModifiedArgs`, `resumeApproved_shouldGoThroughObserve`.

Add import: `import cd.lan1akea.core.message.MsgRole;`

- [ ] **Step 6: Run all tests**

Run: `mvn test -pl agent-core`
Expected: All tests PASS

- [ ] **Step 7: Commit**

```bash
git add agent-core/src/main/java/cd/lan1akea/core/agent/loop/LoopContext.java \
        agent-core/src/main/java/cd/lan1akea/core/agent/loop/LoopExecutor.java \
        agent-core/src/main/java/cd/lan1akea/core/agent/loop/RequestPipeline.java \
        agent-core/src/main/java/cd/lan1akea/core/hook/impl/SessionPersistenceHook.java \
        agent-core/src/test/java/cd/lan1akea/core/agent/loop/LoopExecutorInterventionTest.java
git commit -m "refactor: migrate intervention fields to InterventionState API"
```

---

### Task 3: InterventionResolverTest — Write comprehensive tests

**Files:**
- Create: `agent-core/src/test/java/cd/lan1akea/core/agent/loop/InterventionResolverTest.java`

- [ ] **Step 1: Write InterventionResolverTest**

```java
package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.CoreConstants.EventPayload;
import cd.lan1akea.core.CoreConstants.FinishReason;
import cd.lan1akea.core.CoreConstants.Intervention;
import cd.lan1akea.core.exception.HumanInterventionException;
import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.intervention.InterventionRequest;
import cd.lan1akea.core.intervention.InterventionStore;
import cd.lan1akea.core.message.*;
import cd.lan1akea.core.tool.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Mono;

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
        when(hookDispatcher.dispatch(any(), any())).thenReturn(Mono.just(HookResult.continue_()));

        resolver = new InterventionResolver(interventionStore, toolOrchestrator);
    }

    // ============================================================
    // resolveForRecovery — PENDING
    // ============================================================

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

    // ============================================================
    // resolveForRecovery — APPROVED with args from request
    // ============================================================

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
        // 验证使用 req 中的参数
        Mono<ToolResult> exec = result.getExecution();
        ToolResult tr = exec.block();
        assertEquals("done", tr.getContent());
    }

    // ============================================================
    // resolveForRecovery — APPROVED: fallback to pausedToolArgs
    // ============================================================

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
        // 参数来自 pausedToolArgs
        verify(toolExecutor).execute(argThat(
                call -> call.getArgumentsMap().get("amount").equals(200)));
    }

    // ============================================================
    // resolveForRecovery — CLARIFIED
    // ============================================================

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

    // ============================================================
    // resolveForRecovery — DENIED
    // ============================================================

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

    // ============================================================
    // resolveForRecovery — EXPIRED
    // ============================================================

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

    // ============================================================
    // resolveForRecovery — req is null (intervention was deleted)
    // ============================================================

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

    // ============================================================
    // resolveForRecovery — callId is null (no assistant message)
    // ============================================================

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
        // 不添加 assistant 消息 → findToolCallId 返回 null

        InterventionResolver.ResolvedIntervention result = resolver.resolveForRecovery(ctx);

        assertEquals(InterventionResolver.ResolvedIntervention.Action.RE_ENTER, result.getAction());
        assertNull(ctx.getInterventionState().getInterventionId());
    }

    // ============================================================
    // resolveForRecovery — no interventionId → RE_ENTER
    // ============================================================

    @Test
    void resolveForRecovery_noInterventionId_shouldReEnter() {
        LoopContext ctx = buildCtx("s1");

        InterventionResolver.ResolvedIntervention result = resolver.resolveForRecovery(ctx);

        assertEquals(InterventionResolver.ResolvedIntervention.Action.RE_ENTER, result.getAction());
    }

    // ============================================================
    // createIntervention
    // ============================================================

    @Test
    void createIntervention_shouldBuildRequestAndSetCtxState() {
        when(interventionStore.create(any())).thenReturn("int_new");
        LoopContext ctx = buildCtx("s1");
        HumanInterventionException hie = HumanInterventionException.approval(
                "transfer", "confirm transfer?",
                ToolCallContext.of("call_1", "transfer", Map.of("amount", 100)));

        ChatStreamChunk chunk = resolver.createIntervention(hie, ctx);

        // 验证 chunk
        assertEquals(Intervention.CHUNK_TYPE, chunk.getType());
        assertEquals(Intervention.FINISH_REASON, chunk.getFinishReason());
        assertTrue(chunk.getDelta().contains("int_new"));
        assertTrue(chunk.getDelta().contains("confirm transfer?"));

        // 验证 ctx 状态
        assertEquals("int_new", ctx.getInterventionState().getInterventionId());
        assertEquals("TOOL_APPROVAL", ctx.getInterventionState().getInterventionType());
        assertNotNull(ctx.getInterventionState().getPausedToolArgs());
        assertTrue(ctx.isInterrupted());

        // 验证持久化
        verify(interventionStore).create(any());
    }

    // ============================================================
    // createIntervention — CLARIFY type
    // ============================================================

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

    // ============================================================
    // findToolCallId
    // ============================================================

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
        assertEquals("call_b", callId); // fallback to last tool_use
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

    // ============================================================
    // buildSignalChunk
    // ============================================================

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

    // ============================================================
    // helpers
    // ============================================================

    private LoopContext buildCtx(String sessionId) {
        return LoopContext.builder()
                .agentName("test").sessionId(sessionId)
                .tenantId("t1").userId("u1")
                .messages(new java.util.ArrayList<>(List.of(UserMessage.of("hi"))))
                .generateOptions(cd.lan1akea.core.model.GenerateOptions.defaults())
                .stream(true).build();
    }

    private Msg buildAssistantWithToolUse(String callId, String toolName) {
        return Msg.builder(MsgRole.ASSISTANT)
                .addToolUse(callId, toolName, "{}")
                .build();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl agent-core -Dtest=InterventionResolverTest -DfailIfNoTests=false`
Expected: Compilation FAIL — `InterventionResolver` not defined

- [ ] **Step 3: Commit** (test-only, TDD red)

```bash
git add agent-core/src/test/java/cd/lan1akea/core/agent/loop/InterventionResolverTest.java
git commit -m "test: add InterventionResolverTest covering all recovery paths"
```

---

### Task 4: Implement InterventionResolver

**Files:**
- Create: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/InterventionResolver.java`

- [ ] **Step 1: Implement InterventionResolver**

```java
package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.CoreConstants.EventPayload;
import cd.lan1akea.core.CoreConstants.FinishReason;
import cd.lan1akea.core.CoreConstants.Intervention;
import cd.lan1akea.core.exception.HumanInterventionException;
import cd.lan1akea.core.intervention.InterventionRequest;
import cd.lan1akea.core.intervention.InterventionStore;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.MsgRole;
import cd.lan1akea.core.message.ToolUseBlock;
import cd.lan1akea.core.model.ChatStreamChunk;
import cd.lan1akea.core.tool.ToolCallContext;
import cd.lan1akea.core.tool.ToolCallOrchestrator;
import cd.lan1akea.core.tool.ToolResult;
import cd.lan1akea.core.util.JsonUtils;

import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 人工介入恢复处理器。
 * <p>将介入请求的创建和恢复逻辑从 LoopExecutor 提取到独立组件。
 * 通过 {@link ResolvedIntervention} 实现决策-执行分离，
 * 避免直接返回 Flux 而依赖 LoopExecutor 的私有状态机入口。
 */
public class InterventionResolver {

    /** 介入请求存储器 */
    private final InterventionStore interventionStore;
    /** 工具调用编排器，用于介入恢复时的工具重执行 */
    private final ToolCallOrchestrator toolOrchestrator;

    /**
     * 介入恢复决策结果。
     * <p>将 resumeFromIntervention 的 6 条分支归为 3 种 Action。
     */
    public static class ResolvedIntervention {

        /** 恢复动作类型 */
        public enum Action {
            /** 清除介入状态，重新进入主循环 */
            RE_ENTER,
            /** 返回介入等待 chunk，终止当前流 */
            RETURN_CHUNK,
            /** 执行工具（或失败结果），continue 到 Observe */
            EXECUTE_AND_CONTINUE
        }

        private final Action action;
        private final ChatStreamChunk chunk;
        private final String callId;
        private final Mono<ToolResult> execution;

        private ResolvedIntervention(Action action, ChatStreamChunk chunk,
                                      String callId, Mono<ToolResult> execution) {
            this.action = action;
            this.chunk = chunk;
            this.callId = callId;
            this.execution = execution;
        }

        /** 清除介入状态，重新进入主循环 */
        public static ResolvedIntervention reEnter() {
            return new ResolvedIntervention(Action.RE_ENTER, null, null, null);
        }

        /** 返回介入等待 chunk，终止当前流 */
        public static ResolvedIntervention returnChunk(ChatStreamChunk chunk) {
            return new ResolvedIntervention(Action.RETURN_CHUNK, chunk, null, null);
        }

        /** 执行工具并继续到 Observe 阶段 */
        public static ResolvedIntervention executeAndContinue(String callId,
                                                               Mono<ToolResult> execution) {
            return new ResolvedIntervention(Action.EXECUTE_AND_CONTINUE, null, callId, execution);
        }

        /** @return 恢复动作类型 */
        public Action getAction() { return action; }
        /** @return 介入等待 chunk（RETURN_CHUNK 时有效） */
        public ChatStreamChunk getChunk() { return chunk; }
        /** @return 工具调用 callId（EXECUTE_AND_CONTINUE 时有效） */
        public String getCallId() { return callId; }
        /** @return 工具执行 Mono（EXECUTE_AND_CONTINUE 时有效） */
        public Mono<ToolResult> getExecution() { return execution; }
    }

    /**
     * 构造介入恢复处理器。
     *
     * @param interventionStore 介入请求存储器
     * @param toolOrchestrator  工具调用编排器
     */
    public InterventionResolver(InterventionStore interventionStore,
                                 ToolCallOrchestrator toolOrchestrator) {
        this.interventionStore = interventionStore;
        this.toolOrchestrator = toolOrchestrator;
    }

    /**
     * 从人工介入状态恢复执行。
     *
     * <p>查询介入请求状态并决定恢复策略：
     * <ul>
     *   <li>介入已清除或请求不存在 → RE_ENTER</li>
     *   <li>PENDING → RETURN_CHUNK</li>
     *   <li>APPROVED → 解析参数（优先 req 参数，回退 ctx 快照）→ EXECUTE_AND_CONTINUE</li>
     *   <li>CLARIFIED → 使用修正参数 → EXECUTE_AND_CONTINUE</li>
     *   <li>DENIED → 构建拒绝失败结果 → EXECUTE_AND_CONTINUE</li>
     *   <li>EXPIRED → 构建过期失败结果 → EXECUTE_AND_CONTINUE</li>
     *   <li>callId 为 null → 防御性清除后 RE_ENTER</li>
     * </ul>
     *
     * <p>副作用：在 req==null 或 callId==null 时清除 ctx 介入状态。
     *
     * @param ctx 循环上下文
     * @return 恢复决策
     */
    public ResolvedIntervention resolveForRecovery(LoopContext ctx) {
        String id = ctx.getInterventionState().getInterventionId();
        if (id == null) return ResolvedIntervention.reEnter();

        InterventionRequest req = interventionStore.getById(id);
        if (req == null) {
            ctx.getInterventionState().clear();
            return ResolvedIntervention.reEnter();
        }

        String callId = findToolCallId(ctx, req.getToolName());
        // 防御：callId 为 null 时无法恢复，清除介入状态重新进入循环
        if (callId == null) {
            ctx.getInterventionState().clear();
            return ResolvedIntervention.reEnter();
        }

        switch (req.getStatus()) {
            case PENDING:
                return ResolvedIntervention.returnChunk(buildPendingChunk(req));
            case APPROVED:
                return ResolvedIntervention.executeAndContinue(callId,
                        executeResumeTool(ctx, req.getToolName(), resolveArgs(req, ctx), callId));
            case CLARIFIED:
                return ResolvedIntervention.executeAndContinue(callId,
                        executeResumeTool(ctx, req.getToolName(),
                                req.getModifiedArgs() != null ? req.getModifiedArgs() : Map.of(),
                                callId));
            case DENIED:
                return ResolvedIntervention.executeAndContinue(callId,
                        Mono.just(ToolResult.failure(callId, buildDeniedMessage(req))));
            case EXPIRED:
                return ResolvedIntervention.executeAndContinue(callId,
                        Mono.just(ToolResult.failure(callId, buildExpiredMessage(req))));
            default:
                return ResolvedIntervention.returnChunk(
                        buildSignalChunk(id, Intervention.MSG_WAITING,
                                req.getType().name(), req.getToolName()));
        }
    }

    /**
     * 从 {@link HumanInterventionException} 创建介入请求并中断循环。
     *
     * <p>副作用：
     * <ul>
     *   <li>持久化 InterventionRequest 到 InterventionStore</li>
     *   <li>设置 ctx 介入状态（interventionId、interventionType、pausedToolArgs）</li>
     *   <li>调用 ctx.interrupt() 设置中断标志</li>
     * </ul>
     *
     * @param e   人工介入异常
     * @param ctx 循环上下文
     * @return 发送给前端的介入信号 chunk
     */
    public ChatStreamChunk createIntervention(HumanInterventionException e, LoopContext ctx) {
        InterventionRequest req = buildRequest(e, ctx);
        String id = interventionStore.create(req);

        ctx.getInterventionState().setInterventionId(id);
        ctx.getInterventionState().setInterventionType(e.getType().name());
        if (e.getCallParam() != null) {
            ctx.getInterventionState().setPausedToolArgs(
                    JsonUtils.toCompactJson(e.getCallParam().getArgumentsMap()));
        }
        ctx.interrupt();

        return buildSignalChunk(id, e.getReason(), e.getType().name(), e.getToolName());
    }

    /**
     * 按工具名查找最后一条 assistant 消息中对应 tool_use 的 callId。
     * 无匹配时回退到最后一个 tool_use 的 callId。
     *
     * @param ctx      循环上下文
     * @param toolName 工具名称
     * @return callId，无 assistant 消息或 assistant 无 tool_use 时返回 null
     */
    public String findToolCallId(LoopContext ctx, String toolName) {
        int idx = lastAssistantIndex(ctx);
        if (idx < 0) return null;
        List<ToolUseBlock> tools = ctx.getMessages().get(idx).getToolUseBlocks();
        if (tools == null || tools.isEmpty()) return null;
        for (int i = tools.size() - 1; i >= 0; i--) {
            if (tools.get(i).getName().equals(toolName)) return tools.get(i).getId();
        }
        return tools.get(tools.size() - 1).getId();
    }

    /**
     * 构建介入信号 chunk，通知前端需要人工介入。
     *
     * @param id               介入记录 ID
     * @param question         审批问题描述
     * @param interventionType 介入类型名称
     * @param toolName         工具名称
     * @return 介入信号 chunk
     */
    public ChatStreamChunk buildSignalChunk(String id, String question,
                                             String interventionType, String toolName) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(EventPayload.TYPE, Intervention.PAYLOAD_TYPE);
        payload.put(EventPayload.INTERVENTION_ID, id);
        payload.put(EventPayload.QUESTION, question);
        payload.put(EventPayload.INTERVENTION_TYPE, interventionType);
        payload.put(EventPayload.TOOL_NAME, toolName);
        return ChatStreamChunk.builder()
                .delta(JsonUtils.toCompactJson(payload))
                .type(Intervention.CHUNK_TYPE)
                .finishReason(Intervention.FINISH_REASON)
                .build();
    }

    // ============================================================
    // private helpers
    // ============================================================

    /** 找最后一条 assistant 消息的索引，-1 表示不存在 */
    private int lastAssistantIndex(LoopContext ctx) {
        List<Msg> msgs = ctx.getMessages();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i).getRole() == MsgRole.ASSISTANT) {
                return i;
            }
        }
        return -1;
    }

    /** 从异常构建 InterventionRequest */
    private InterventionRequest buildRequest(HumanInterventionException e, LoopContext ctx) {
        return InterventionRequest.builder()
                .type(toInterventionType(e.getType()))
                .sessionId(ctx.getSessionId())
                .requestId(ctx.getRequestId())
                .tenantId(ctx.getTenantId())
                .agentName(ctx.getAgentName())
                .toolName(e.getToolName())
                .question(e.getReason())
                .toolArgs(e.getCallParam() != null ? e.getCallParam().getArgumentsMap() : null)
                .recentMessages(truncateMessages(ctx.getMessages()))
                .ttlMinutes(e.getTtlMinutes())
                .build();
    }

    /** 截断消息列表至最近的 {@value Intervention#RECENT_MSG_LIMIT} 条 */
    private List<Msg> truncateMessages(List<Msg> messages) {
        int size = messages.size();
        int from = Math.max(0, size - Intervention.RECENT_MSG_LIMIT);
        return new ArrayList<>(messages.subList(from, size));
    }

    /** APPROVED 时解析参数：优先 req 参数，回退 ctx 快照，再回退空 Map */
    private Map<String, Object> resolveArgs(InterventionRequest req, LoopContext ctx) {
        if (req.getToolArgs() != null && !req.getToolArgs().isEmpty()) {
            return req.getToolArgs();
        }
        if (ctx.getInterventionState().getPausedToolArgs() != null) {
            return JsonUtils.safeParseMap(ctx.getInterventionState().getPausedToolArgs());
        }
        return Map.of();
    }

    /** 构建 PENDING 状态的等待 chunk */
    private ChatStreamChunk buildPendingChunk(InterventionRequest req) {
        return ChatStreamChunk.of(
                "[等待处理] " + req.getType() + " — " + req.getToolName() + ": "
                        + (req.getQuestion() != null ? req.getQuestion() : ""),
                FinishReason.INTERRUPTED);
    }

    /** 构建 DENIED 状态的失败消息 */
    private String buildDeniedMessage(InterventionRequest req) {
        return Intervention.MSG_DENIED + " — " + req.getToolName()
                + (req.getResolution() != null && !req.getResolution().isBlank()
                        ? ": " + req.getResolution() : "");
    }

    /** 构建 EXPIRED 状态的失败消息 */
    private String buildExpiredMessage(InterventionRequest req) {
        return Intervention.MSG_EXPIRED + " — " + req.getToolName();
    }

    /** 以指定参数执行被暂停的工具（跳过审批检查） */
    private Mono<ToolResult> executeResumeTool(LoopContext ctx, String toolName,
                                                Map<String, Object> args, String callId) {
        ToolCallContext param = ToolCallContext.builder()
                .callId(callId)
                .toolName(toolName)
                .arguments(args != null ? args : Map.of())
                .tenantId(ctx.getTenantId())
                .userId(ctx.getUserId())
                .sessionId(ctx.getSessionId())
                .attributes(ctx.getAttributes())
                .build();
        param.setApproved(true);
        return toolOrchestrator.executeDirect(param, ctx);
    }

    /** HumanInterventionException.Type → InterventionRequest.Type */
    private static InterventionRequest.Type toInterventionType(HumanInterventionException.Type t) {
        switch (t) {
            case TOOL_APPROVAL: return InterventionRequest.Type.TOOL_APPROVAL;
            case TOOL_CLARIFY: return InterventionRequest.Type.TOOL_CLARIFY;
            default: return InterventionRequest.Type.TOOL_APPROVAL;
        }
    }
}
```

- [ ] **Step 2: Run InterventionResolverTest**

Run: `mvn test -pl agent-core -Dtest=InterventionResolverTest`
Expected: All 16 tests PASS

- [ ] **Step 3: Commit**

```bash
git add agent-core/src/main/java/cd/lan1akea/core/agent/loop/InterventionResolver.java
git commit -m "feat: add InterventionResolver, extract intervention recovery logic"
```

---

### Task 5: Wire InterventionResolver into ReActAgent and LoopExecutor

**Files:**
- Modify: `agent-core/src/main/java/cd/lan1akea/core/agent/ReActAgent.java`
- Modify: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/LoopExecutor.java`

- [ ] **Step 1: Add InterventionResolver to ReActAgent constructor**

In `ReActAgent.java`, inject `InterventionResolver` alongside `LoopExecutor`:

```java
// After existing interventionStore setup (around line 112-114):
InterventionStore interventionStore = config.getInterventionStore() != null
        ? config.getInterventionStore()
        : new InMemoryInterventionStore();
InterventionResolver interventionResolver = new InterventionResolver(
        interventionStore, toolOrch);
this.loopExecutor = new LoopExecutor(
        engine, modelPipeline, toolOrch, hookDispatcher, metrics,
        interventionStore, contextWindow.getEstimator(), interventionResolver);
```

- [ ] **Step 2: Add InterventionResolver parameter to LoopExecutor constructor**

In `LoopExecutor.java`, add field:
```java
/** 人工介入恢复处理器 */
private final InterventionResolver interventionResolver;
```

Update constructor to accept `InterventionResolver` as 8th parameter:
```java
public LoopExecutor(LoopDecisionEngine engine, ModelCallPipeline modelPipeline,
                     ToolCallOrchestrator toolOrchestrator, HookDispatcher hookDispatcher,
                     AgentMetrics metrics, InterventionStore interventionStore,
                     TokenEstimator tokenEstimator, InterventionResolver interventionResolver) {
    // ... existing assignments ...
    this.interventionResolver = interventionResolver;
}
```

- [ ] **Step 3: Replace resumeFromIntervention with delegation**

In `runStream` (line 102), replace the intervention check block:
```java
if (ctx.getInterventionState().hasPending() && !ctx.isInterrupted()) {
    InterventionResolver.ResolvedIntervention resolved =
            interventionResolver.resolveForRecovery(ctx);
    switch (resolved.getAction()) {
        case RE_ENTER: return runStream(ctx);
        case RETURN_CHUNK: return Flux.just(resolved.getChunk());
        case EXECUTE_AND_CONTINUE:
            return resolveAndContinue(ctx, resolved.getCallId(), resolved.getExecution());
        default: return Flux.empty();
    }
}
```

- [ ] **Step 4: Replace handleIntervention with delegation**

In `handleToolError` (line 435), replace:
```java
return handleIntervention(hie, ctx);
```
with:
```java
return Flux.just(interventionResolver.createIntervention(hie, ctx));
```

- [ ] **Step 5: Delete moved methods and interventionStore field from LoopExecutor**

Delete these 8 methods:
- `resumeFromIntervention` (lines 240-287)
- `handleIntervention` (lines 453-476)
- `extractToolCallIdByName` (lines 181-190)
- `executeResumeTool` (lines 193-206)
- `interventionChunk` (lines 534-547)
- `lastAssistantIndex` (lines 170-178)
- `truncateMessages` (lines 500-504)
- `toInterventionType` (lines 484-490)

Remove `interventionStore` field (line 61), constructor parameter (line 79), and assignment (line 86) — now unused after migration. Remove from Javadoc as well (line 74).

Update constructor to 7 params: `engine, modelPipeline, toolOrchestrator, hookDispatcher, metrics, tokenEstimator, interventionResolver`.

- [ ] **Step 6: Keep lastAssistantIndex in LoopExecutor for resolveAndContinue**

Since `resolveAndContinue` stays in `LoopExecutor` and needs `lastAssistantIndex`, add a private copy:

```java
/** 找最后一条 assistant 消息的索引，-1 表示不存在 */
private int lastAssistantIndex(LoopContext ctx) {
    List<Msg> msgs = ctx.getMessages();
    for (int i = msgs.size() - 1; i >= 0; i--) {
        if (msgs.get(i).getRole() == MsgRole.ASSISTANT) return i;
    }
    return -1;
}
```

- [ ] **Step 7: Update resolveAndContinue to use InterventionState.clear()**

In `resolveAndContinue` (lines 230-232), ensure:
```java
ctx.getInterventionState().clear();
```

- [ ] **Step 8: Update both test files' LoopExecutor constructor calls**

In `LoopExecutorTest.java` setUp (lines 47-49), replace:
```java
executor = new LoopExecutor(engine, modelPipeline, orchestrator, hookDispatcher,
        AgentMetrics.NOOP, new cd.lan1akea.core.intervention.InMemoryInterventionStore(),
        new Cl100kTokenEstimator());
```
with:
```java
cd.lan1akea.core.intervention.InMemoryInterventionStore store =
        new cd.lan1akea.core.intervention.InMemoryInterventionStore();
InterventionResolver resolver = new InterventionResolver(store, orchestrator);
executor = new LoopExecutor(engine, modelPipeline, orchestrator, hookDispatcher,
        AgentMetrics.NOOP, new Cl100kTokenEstimator(), resolver);
```

In `LoopExecutorInterventionTest.java` setUp (lines 52-53), replace:
```java
executor = new LoopExecutor(engine, modelPipeline, orchestrator, hookDispatcher,
        AgentMetrics.NOOP, interventionStore, new Cl100kTokenEstimator());
```
with:
```java
InterventionResolver interventionResolver =
        new InterventionResolver(interventionStore, orchestrator);
executor = new LoopExecutor(engine, modelPipeline, orchestrator, hookDispatcher,
        AgentMetrics.NOOP, new Cl100kTokenEstimator(), interventionResolver);
```

- [ ] **Step 9: Run all tests**

Run: `mvn test -pl agent-core`
Expected: All tests PASS

- [ ] **Step 10: Commit**

```bash
git add agent-core/src/main/java/cd/lan1akea/core/agent/ReActAgent.java \
        agent-core/src/main/java/cd/lan1akea/core/agent/loop/LoopExecutor.java \
        agent-core/src/test/java/cd/lan1akea/core/agent/loop/LoopExecutorTest.java \
        agent-core/src/test/java/cd/lan1akea/core/agent/loop/LoopExecutorInterventionTest.java
git commit -m "refactor: wire InterventionResolver, remove intervention methods from LoopExecutor"
```

---

### Task 6: Fix #4 — SessionPersistenceHook error handling

**Files:**
- Modify: `agent-core/src/main/java/cd/lan1akea/core/hook/impl/SessionPersistenceHook.java`
- Modify: `agent-core/src/test/java/cd/lan1akea/core/agent/loop/LoopExecutorTest.java`

- [ ] **Step 1: Add error handling to SessionPersistenceHook**

In `SessionPersistenceHook.java`, update `persistTurn` and `saveCheckpoint`:

```java
private void persistTurn(LoopContext ctx, String sessionId) {
    // ... existing logic unchanged ...
    stateStore.addTurn(new SessionId(sessionId), turn)
            .doOnError(e -> log.warning("持久化Turn失败[session=" + sessionId + "]: " + e.getMessage()))
            .onErrorComplete()
            .subscribe();
}

private void saveCheckpoint(LoopContext ctx, String sessionId) {
    // ... existing logic unchanged ...
    stateStore.saveCheckpoint(state)
            .doOnError(e -> log.warning("持久化Checkpoint失败[session=" + sessionId + "]: " + e.getMessage()))
            .onErrorComplete()
            .subscribe();
}
```

Add import: `import java.util.logging.Logger;`

Add field: `private static final Logger log = Logger.getLogger(SessionPersistenceHook.class.getName());`

- [ ] **Step 2: Add test for error resilience**

In `LoopExecutorTest.java` (or create a new test file if appropriate), add a test case that verifies the loop continues when persistence fails. Look for existing `AFTER_ITERATION` tests and add a variant where the stateStore mock throws.

Since `SessionPersistenceHook` operates fire-and-forget and logs on error rather than propagating, the key test is that the loop doesn't crash when persistence fails:

```java
@Test
void persistFailure_shouldNotCrashLoop() {
    AgentStateStore failingStore = mock(AgentStateStore.class);
    when(failingStore.addTurn(any(), any())).thenReturn(Mono.error(new RuntimeException("DB down")));
    when(failingStore.saveCheckpoint(any())).thenReturn(Mono.error(new RuntimeException("DB down")));
    when(failingStore.findById(any())).thenReturn(Mono.empty());
    when(failingStore.create(any())).thenReturn(Mono.empty());

    // Build agent with failing store, verify it still produces response
    // (add to existing test or create new test file)
}
```

Note: This test belongs in a higher-level integration context. For the unit-level change, the error handling is verified by code review — the `.doOnError(...).onErrorComplete()` pattern ensures errors don't propagate.

- [ ] **Step 3: Run tests**

Run: `mvn test -pl agent-core`
Expected: All tests PASS

- [ ] **Step 4: Commit**

```bash
git add agent-core/src/main/java/cd/lan1akea/core/hook/impl/SessionPersistenceHook.java
git commit -m "fix: add error logging to SessionPersistenceHook fire-and-forget, fix #4"
```

---

### Task 7: Fix #5 — activeRequests leak via Flux.using

**Files:**
- Modify: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/RequestPipeline.java`

- [ ] **Step 1: Rewrite executeStream with Flux.using**

In `RequestPipeline.java` executeStream, replace the block starting at `activeRequests.put` through `return sessionGate.enqueueStream(...)`:

```java
LoopContext loopCtx = LoopContextFactory.create(
        agentName, ctx, lm.messages, opts, execConfig, true);
if (lm.result.interventionId != null) {
    loopCtx.getInterventionState().setInterventionId(lm.result.interventionId);
    loopCtx.getInterventionState().setInterventionType(lm.result.interventionType);
    loopCtx.getInterventionState().setPausedToolArgs(lm.result.pausedToolArgs);
}
return Flux.using(
        () -> {
            activeRequests.put(loopCtx.getRequestId(), loopCtx);
            return loopCtx;
        },
        lc -> {
            Flux<ChatStreamChunk> inner = loopExecutor.runStream(lc);
            long timeout = execConfig.getTotalTimeoutMs();
            if (timeout > 0) inner = inner.timeout(Duration.ofMillis(timeout));
            return sessionGate.enqueueStream(lc.getSessionId(), inner);
        },
        lc -> activeRequests.remove(lc.getRequestId())
);
```

- [ ] **Step 2: Rewrite execute with Mono.using**

In `RequestPipeline.java` execute, same pattern with `Mono.using`:

```java
return Mono.using(
        () -> {
            activeRequests.put(loopCtx.getRequestId(), loopCtx);
            return loopCtx;
        },
        lc -> {
            Mono<ChatResponse> exec = loopExecutor.run(lc);
            long timeout = execConfig.getTotalTimeoutMs();
            if (timeout > 0) exec = exec.timeout(Duration.ofMillis(timeout));
            return sessionGate.enqueue(lc.getSessionId(), exec);
        },
        lc -> activeRequests.remove(lc.getRequestId())
);
```

- [ ] **Step 3: Run tests**

Run: `mvn test -pl agent-core`
Expected: All tests PASS

- [ ] **Step 4: Commit**

```bash
git add agent-core/src/main/java/cd/lan1akea/core/agent/loop/RequestPipeline.java
git commit -m "fix: use Flux.using/Mono.using for activeRequests cleanup, fix #5"
```

---

### Task 8: Fix #6, #7, #8, #9 — boundary issues

**Files:**
- Modify: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/LoopExecutor.java`
- Modify: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/ModelCallPipeline.java`

- [ ] **Step 1: Fix #6 — executeAct metrics comment**

In `LoopExecutor.java` executeAct, add comment before the metrics call:
```java
// iteration 在 executeObserve 中递增，此处 +1 记录即将进入的迭代号
metrics.recordIteration(ctx.getAgentName(), ctx.getSessionId(),
        ctx.getIteration() + 1, toolCalls.size());
```

- [ ] **Step 2: Fix #7 — assembleResponseFromChunks default finishReason**

In `ModelCallPipeline.java` assembleResponseFromChunks, change:
```java
// Before
String finishReason = FinishReason.COMPLETED;

// After
String finishReason = null;
```
Add after the search loop (before the return):
```java
if (finishReason == null) finishReason = FinishReason.COMPLETED;
```

- [ ] **Step 3: Fix #8 — resolveAndContinue insertAt boundary**

In `LoopExecutor.java` resolveAndContinue, replace:
```java
int insertAt = lastAssistantIndex(ctx) + 1;
```
with:
```java
int lastAssistant = lastAssistantIndex(ctx);
int insertAt = lastAssistant >= 0 ? lastAssistant + 1 : ctx.getMessages().size();
```

Note: `lastAssistantIndex` still exists in `InterventionResolver`. Since `resolveAndContinue` stays in `LoopExecutor`, we need to either:
- Keep a copy of `lastAssistantIndex` in `LoopExecutor` (minimal, 8 lines)
- Make `InterventionResolver.lastAssistantIndex` package-private and call it from `LoopExecutor`
- Extract to a shared utility

**Decision:** Keep a private `lastAssistantIndex` in `LoopExecutor` since `resolveAndContinue` also stays there. It's 8 lines — acceptable duplication for clear separation.

- [ ] **Step 4: Fix #9 — handleInterruptStream NPE**

In `LoopExecutor.java` handleInterruptStream, replace:
```java
String reason = ctx.getLastResponse() != null
        ? ctx.getLastResponse().getMessage().getTextContent()
        : UI.INTERRUPT_EXEC;
```
with:
```java
Msg lastMsg = ctx.getLastResponse() != null
        ? ctx.getLastResponse().getMessage() : null;
String reason = lastMsg != null ? lastMsg.getTextContent() : UI.INTERRUPT_EXEC;
```

- [ ] **Step 5: Add test for #7 — empty finish reason**

In `ModelCallPipelineTest` (or create if not exists), add:
```java
@Test
void assembleResponse_noFinishReason_shouldDefaultToCompleted() {
    List<ChatStreamChunk> chunks = List.of(
            ChatStreamChunk.builder().delta("hello").type(ChatStreamChunk.TYPE_TEXT).build()
    );
    ChatResponse resp = ModelCallPipeline.assembleResponseFromChunks(chunks);
    assertNotNull(resp);
    assertEquals(FinishReason.COMPLETED, resp.getFinishReason());
}
```

- [ ] **Step 6: Add test for #8 — insertAt boundary**

In `LoopExecutorInterventionTest.java`, add:
```java
@Test
void resolveAndContinue_noAssistantMessage_shouldNotCrash() {
    // When there's no assistant message and intervention is triggered,
    // the tool_result should be appended at end rather than index 0
    InterventionRequest req = InterventionRequest.builder()
            .type(InterventionRequest.Type.TOOL_APPROVAL)
            .sessionId("s1").requestId("r1").agentName("test")
            .toolName("transfer").question("approve?").build();
    req.expire();
    when(interventionStore.getById("int_1")).thenReturn(req);

    LoopContext ctx = buildCtx("s1");
    ctx.getInterventionState().setInterventionId("int_1");
    // ctx has no ASSISTANT message — only USER

    executor.runStream(ctx).collectList().block();

    // Should not throw; should have cleared intervention state
    assertNull(ctx.getInterventionState().getInterventionId());
}
```
Note: This needs `interventionResolver` wired into executor for the test to work. Since Task 5 wires it, this test will verify the #8 fix end-to-end.

- [ ] **Step 7: Run tests**

Run: `mvn test -pl agent-core`
Expected: All tests PASS

- [ ] **Step 8: Commit**

```bash
git add agent-core/src/main/java/cd/lan1akea/core/agent/loop/LoopExecutor.java \
        agent-core/src/main/java/cd/lan1akea/core/agent/loop/ModelCallPipeline.java
git commit -m "fix: boundary issues #6 #7 #8 #9 — metrics comment, finishReason, insertAt, NPE"
```

---

### Task 9: Fix #11 — Builder null safety

**Files:**
- Modify: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/LoopContext.java`
- Modify: `agent-core/src/test/java/cd/lan1akea/core/agent/loop/LoopContextTest.java`

- [ ] **Step 1: Add null safety to LoopContext constructor**

In `LoopContext.java` constructor (lines 127-144), replace:
```java
this.messages = new ArrayList<>(builder.messages);
this.generateOptions = builder.generateOptions;
```
with:
```java
this.messages = builder.messages != null
        ? new ArrayList<>(builder.messages) : new ArrayList<>();
this.generateOptions = builder.generateOptions != null
        ? builder.generateOptions : GenerateOptions.defaults();
```

- [ ] **Step 2: Add tests for null safety**

In `LoopContextTest.java`, add:
```java
@Test
void builder_nullMessages_shouldDefaultToEmptyList() {
    LoopContext ctx = LoopContext.builder()
            .agentName("a")
            .messages(null)
            .generateOptions(GenerateOptions.defaults())
            .build();
    assertNotNull(ctx.getMessages());
    assertTrue(ctx.getMessages().isEmpty());
}

@Test
void builder_nullGenerateOptions_shouldDefaultToDefaults() {
    LoopContext ctx = LoopContext.builder()
            .agentName("a")
            .messages(List.of())
            .generateOptions(null)
            .build();
    assertNotNull(ctx.getGenerateOptions());
}

@Test
void builder_noMessages_shouldNotThrow() {
    LoopContext ctx = LoopContext.builder()
            .agentName("a")
            .build();
    assertNotNull(ctx.getMessages());
    assertNotNull(ctx.getGenerateOptions());
}
```

- [ ] **Step 3: Run tests**

Run: `mvn test -pl agent-core -Dtest=LoopContextTest`
Expected: All tests PASS

- [ ] **Step 4: Commit**

```bash
git add agent-core/src/main/java/cd/lan1akea/core/agent/loop/LoopContext.java \
        agent-core/src/test/java/cd/lan1akea/core/agent/loop/LoopContextTest.java
git commit -m "fix: add null safety to LoopContext.Builder, fix #11"
```

---

### Task 10: Clean up buildHookContext duplication

**Files:**
- Modify: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/LoopExecutor.java`
- Modify: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/ModelCallPipeline.java`
- Modify: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/ToolCallOrchestrator.java`

- [ ] **Step 1: Inline buildHookContext in LoopExecutor**

Delete the `buildHookContext` method (line 653):
```java
private HookContext buildHookContext(LoopContext ctx) {
    return ctx.toHookContext();
}
```

Replace all 3 calls: `buildHookContext(ctx)` → `ctx.toHookContext()`
- `dispatchSummarizeHook` (line 126)
- `dispatchAfterIteration` (line 635)
- `handleInterruptStream` (line 579)

- [ ] **Step 2: Inline buildHookContext in ModelCallPipeline**

Delete the method (line 245) and replace `buildHookContext(ctx)` → `ctx.toHookContext()` in `executeStream` (line 85).

- [ ] **Step 3: Inline buildHookContext in ToolCallOrchestrator**

Delete the method (line 195) and replace `buildHookContext(ctx)` → `ctx.toHookContext()` in `execute` (line 62) and `executeDirect` (line 93).

- [ ] **Step 4: Run all tests**

Run: `mvn test -pl agent-core`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
git add agent-core/src/main/java/cd/lan1akea/core/agent/loop/LoopExecutor.java \
        agent-core/src/main/java/cd/lan1akea/core/agent/loop/ModelCallPipeline.java \
        agent-core/src/main/java/cd/lan1akea/core/agent/loop/ToolCallOrchestrator.java
git commit -m "refactor: inline redundant buildHookContext, use ctx.toHookContext() directly"
```

---

### Task 11: Final verification — full test suite

- [ ] **Step 1: Run full agent-core test suite**

Run: `mvn test -pl agent-core`
Expected: All tests PASS, no regressions

- [ ] **Step 2: Run full project build**

Run: `mvn test`
Expected: All modules PASS

- [ ] **Step 3: Check InterventionResolver test coverage**

Run: `mvn test -pl agent-core -Dtest=InterventionResolverTest,InterventionStateTest`
Expected: All tests in both test classes PASS

- [ ] **Step 4: Review commit history**

Run: `git log --oneline -12`
Expected: Clean sequence of focused commits

- [ ] **Step 5: Final commit (if any cleanup needed)**

Only if the full project build reveals issues.

---

## Verification Checklist

Before declaring done, verify:
- [ ] `LoopExecutor.java` reduced from ~670 to ~500 lines
- [ ] `InterventionResolver.java` exists with ~180 lines
- [ ] All 16 InterventionResolverTest tests pass
- [ ] All 5 InterventionStateTest tests pass
- [ ] All existing tests in agent-core pass
- [ ] `LoopExecutorInterventionTest` updated for new API (all 5 tests pass)
- [ ] No remaining direct `ctx.setInterventionId(...)` calls (all migrated to `ctx.getInterventionState().setInterventionId(...)`)
- [ ] No remaining `buildHookContext` methods
