# Observe 阶段闭环实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 LoopDecisionEngine 成为 ReAct 状态机唯一路由裁判，dispatchAfterIteration 收敛至 Observe 一处。

**Architecture:** 新增 executePhase 递归路由中枢，每个阶段执行后回访引擎获取下一阶段。LoopContext 新增 complete 标记，引擎 Guard 检查 complete 返回 Stop 终止循环。executeReason/executeAct 剥离路由逻辑，executeObserve 统一负责 iteration++ 和 dispatchAfterIteration。

**Tech Stack:** Java 17, Reactor (Flux/Mono), JUnit 5 + Mockito, Maven

---

## 文件结构

| 文件 | 职责 |
|------|------|
| `LoopContext.java` | 新增 `complete` 标记 + 访问器 |
| `LoopDecisionEngine.java` | 复活全部阶段评估，Guard 检查 complete 返回 Stop |
| `LoopExecutor.java` | 新增 executePhase 路由中枢，剥离 Reason/Act 路由，新增 executeObserve，删除 executeAndContinue |
| `LoopDecisionEngineTest.java` | 新增：Reason 评估有工具/无工具、Guard→Stop、Observe→Guard |
| `LoopExecutorTest.java` | 新增：无工具走 Observe、有工具走 Observe、Observe 递增 iteration |
| `LoopExecutorInterventionTest.java` | 更新：介入恢复验证走 Observe 路径 |
| `LoopContextTest.java` | 新增：markComplete/isComplete 测试 |

---

### Task 1: LoopContext 新增 complete 标记

**Files:**
- Modify: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/LoopContext.java`
- Modify: `agent-core/src/test/java/cd/lan1akea/core/agent/loop/LoopContextTest.java`

- [ ] **Step 1: 在 LoopContext 中新增 complete 字段和方法**

在 `LoopContext.java` 的 `pausedToolArgs` 字段之后添加：

```java
/**
 * 会话是否已完成（无需继续推理）。
 * 由引擎在 REASON 阶段评估无工具调用时标记，
 * Guard 阶段检查此标记决定 Stop。
 */
private volatile boolean complete;

/** 标记会话完成，下一轮 Guard 评估时将返回 Stop */
public void markComplete() { this.complete = true; }

/** @return 会话是否已完成 */
public boolean isComplete() { return complete; }
```

- [ ] **Step 2: 新增 LoopContextTest 测试用例**

在 `LoopContextTest.java` 末尾添加：

```java
@Test
void markComplete_shouldSetCompleteFlag() {
    LoopContext ctx = LoopContext.builder()
            .agentName("a").messages(List.of())
            .generateOptions(GenerateOptions.defaults()).build();
    assertFalse(ctx.isComplete());
    ctx.markComplete();
    assertTrue(ctx.isComplete());
}

@Test
void newContext_shouldNotBeComplete() {
    LoopContext ctx = LoopContext.builder()
            .agentName("a").messages(List.of())
            .generateOptions(GenerateOptions.defaults()).build();
    assertFalse(ctx.isComplete());
}
```

- [ ] **Step 3: 运行测试验证**

```bash
mvn test -pl agent-core -Dtest=LoopContextTest -DfailIfNoTests=false
```
Expected: 全部通过（含新增 2 个用例）

- [ ] **Step 4: 提交**

```bash
git add agent-core/src/main/java/cd/lan1akea/core/agent/loop/LoopContext.java agent-core/src/test/java/cd/lan1akea/core/agent/loop/LoopContextTest.java
git commit -m "feat: add complete flag to LoopContext for state machine termination"
```

---

### Task 2: LoopDecisionEngine 复活全部阶段评估

**Files:**
- Modify: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/LoopDecisionEngine.java`
- Modify: `agent-core/src/test/java/cd/lan1akea/core/agent/loop/LoopDecisionEngineTest.java`

- [ ] **Step 1: 重写 LoopDecisionEngine.evaluate()**

将 `LoopDecisionEngine.java` 的 `evaluate()` 方法改为按阶段路由，REASON/ACT/OBSERVE 各自有真正的评估逻辑：

```java
/**
 * 评估当前阶段，返回下一步决策。
 *
 * @param current 当前阶段
 * @param ctx     循环上下文
 * @return 决策（继续或终止）
 */
public Decision evaluate(Phase current, LoopContext ctx) {
    if (current.isGuard()) {
        return evaluateGuard(ctx);
    }
    if (current.isReason()) {
        return evaluateReason(ctx);
    }
    if (current.isAct()) {
        return Decision.continue_(Phase.observe());
    }
    if (current.isObserve()) {
        return Decision.continue_(Phase.guard());
    }
    return Decision.continue_(current);
}

/**
 * Guard 阶段：检查完成标记和最大迭代次数。
 */
private Decision evaluateGuard(LoopContext ctx) {
    if (ctx.isComplete()) {
        Msg lastMsg = ctx.getLastResponse() != null
                ? ctx.getLastResponse().getMessage() : null;
        ChatUsage usage = ctx.getLastResponse() != null
                ? ctx.getLastResponse().getUsage() : new ChatUsage(0, 0);
        return Decision.stop(new ChatResponse(lastMsg, usage, FinishReason.STOP, ""));
    }
    if (ctx.getIteration() >= ctx.getMaxIterations()) {
        ctx.addMessage(SystemMessage.of(
                Prompt.MAX_ITERATIONS_SUMMARY + Prompt.MAX_ITERATIONS_NO_TOOLS));
    }
    return Decision.continue_(Phase.reason());
}

/**
 * REASON 阶段：读 lastResponse 中的 ToolUseBlock 决定下一阶段。
 * 无工具时标记 complete，进入 Observe 做最后一次持久化后终止。
 */
private Decision evaluateReason(LoopContext ctx) {
    ChatResponse resp = ctx.getLastResponse();
    if (resp != null && resp.getMessage() != null) {
        List<ToolUseBlock> tools = resp.getMessage().getToolUseBlocks();
        if (tools != null && !tools.isEmpty()) {
            return Decision.continue_(Phase.act(tools));
        }
    }
    ctx.markComplete();
    return Decision.continue_(Phase.observe());
}
```

删除原有的 `evaluateObserve` 方法（iteration++ 已移至 executeObserve）。

需要新增 import：
```java
import cd.lan1akea.core.message.SystemMessage;
import cd.lan1akea.core.message.ToolUseBlock;
import java.util.List;
```

`buildInterruptedResponse` 保持不变。

- [ ] **Step 2: 重写 LoopDecisionEngineTest**

完整替换 `LoopDecisionEngineTest.java`：

```java
package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.CoreConstants.FinishReason;
import cd.lan1akea.core.CoreConstants.Prompt;
import cd.lan1akea.core.message.*;
import cd.lan1akea.core.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LoopDecisionEngineTest {

    private final LoopDecisionEngine engine = new LoopDecisionEngine();

    private static LoopContext ctx() {
        return LoopContext.builder().agentName("test").messages(List.of()).build();
    }

    // ============================================================
    // Guard 阶段
    // ============================================================

    @Test
    void guardNormal_shouldContinueToReason() {
        LoopContext ctx = ctx();
        Decision d = engine.evaluate(Phase.guard(), ctx);
        assertFalse(d.isStop());
        assertTrue(d.getNextPhase().isReason());
    }

    @Test
    void guardMaxIterations_shouldInjectSummaryAndContinueToReason() {
        LoopContext ctx = LoopContext.builder().agentName("test")
                .messages(List.of()).maxIterations(3).build();
        ctx.setIteration(3);
        Decision d = engine.evaluate(Phase.guard(), ctx);
        assertFalse(d.isStop());
        assertTrue(d.getNextPhase().isReason());
        String lastMsg = ctx.getMessages().get(ctx.getMessages().size() - 1).getTextContent();
        assertTrue(lastMsg.contains(Prompt.MAX_ITERATIONS_SUMMARY));
    }

    @Test
    void guardComplete_shouldReturnStop() {
        LoopContext ctx = ctx();
        Msg assistantMsg = AssistantMessage.of("done");
        ctx.setLastResponse(new ChatResponse(assistantMsg, new ChatUsage(10, 5), FinishReason.STOP, ""));
        ctx.markComplete();
        Decision d = engine.evaluate(Phase.guard(), ctx);
        assertTrue(d.isStop());
        assertNotNull(d.getResponse());
        assertEquals(FinishReason.STOP, d.getResponse().getFinishReason());
    }

    // ============================================================
    // REASON 阶段 — 引擎真正评估工具调用
    // ============================================================

    @Test
    void reasonWithTools_shouldContinueToAct() {
        LoopContext ctx = ctx();
        Msg msg = AssistantMessage.builder()
                .addToolUse("call_1", "search", "{}")
                .build();
        ctx.setLastResponse(new ChatResponse(msg, new ChatUsage(0, 0), "tool_calls", ""));
        Decision d = engine.evaluate(Phase.reason(), ctx);
        assertFalse(d.isStop());
        assertTrue(d.getNextPhase().isAct());
        assertNotNull(d.getNextPhase().getToolCalls());
        assertEquals(1, d.getNextPhase().getToolCalls().size());
    }

    @Test
    void reasonWithoutTools_shouldMarkCompleteAndContinueToObserve() {
        LoopContext ctx = ctx();
        Msg msg = AssistantMessage.of("all done");
        ctx.setLastResponse(new ChatResponse(msg, new ChatUsage(5, 3), FinishReason.STOP, ""));
        Decision d = engine.evaluate(Phase.reason(), ctx);
        assertFalse(d.isStop());
        assertTrue(d.getNextPhase().isObserve());
        assertTrue(ctx.isComplete());
    }

    @Test
    void reasonNullResponse_shouldMarkCompleteAndGoToObserve() {
        LoopContext ctx = ctx();
        // lastResponse 为 null 时的兜底
        Decision d = engine.evaluate(Phase.reason(), ctx);
        assertFalse(d.isStop());
        assertTrue(d.getNextPhase().isObserve());
        assertTrue(ctx.isComplete());
    }

    // ============================================================
    // ACT 阶段
    // ============================================================

    @Test
    void act_shouldContinueToObserve() {
        LoopContext ctx = ctx();
        Decision d = engine.evaluate(Phase.act(List.of()), ctx);
        assertFalse(d.isStop());
        assertTrue(d.getNextPhase().isObserve());
    }

    // ============================================================
    // OBSERVE 阶段
    // ============================================================

    @Test
    void observe_shouldContinueToGuard() {
        LoopContext ctx = ctx();
        Decision d = engine.evaluate(Phase.observe(), ctx);
        assertFalse(d.isStop());
        assertTrue(d.getNextPhase().isGuard());
    }

    // ============================================================
    // buildInterruptedResponse
    // ============================================================

    @Test
    void buildInterruptedResponse_shouldReturnChatResponse() {
        ChatResponse resp = LoopDecisionEngine.buildInterruptedResponse("test-reason");
        assertNotNull(resp);
        assertEquals(FinishReason.INTERRUPTED, resp.getFinishReason());
        assertNotNull(resp.getMessage());
        assertTrue(resp.getMessage().getTextContent().contains("test-reason"));
    }
}
```

- [ ] **Step 3: 运行测试验证**

```bash
mvn test -pl agent-core -Dtest=LoopDecisionEngineTest -DfailIfNoTests=false
```
Expected: 全部 9 个测试通过

- [ ] **Step 4: 提交**

```bash
git add agent-core/src/main/java/cd/lan1akea/core/agent/loop/LoopDecisionEngine.java agent-core/src/test/java/cd/lan1akea/core/agent/loop/LoopDecisionEngineTest.java
git commit -m "feat: resurrect all phase evaluations in LoopDecisionEngine, add Guard stop on complete"
```

---

### Task 3: LoopExecutor 核心重构

**Files:**
- Modify: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/LoopExecutor.java`
- Modify: `agent-core/src/test/java/cd/lan1akea/core/agent/loop/LoopExecutorTest.java`
- Modify: `agent-core/src/test/java/cd/lan1akea/core/agent/loop/LoopExecutorInterventionTest.java`

- [ ] **Step 1: 重写 executeReason — 剥离路由逻辑**

将当前 `executeReason` 方法替换为（删除工具调用检查逻辑，统一追加 assistant 消息）：

```java
/**
 * 执行推理阶段：调用模型获取回复。
 *
 * <p>流式收集模型分块 → 组装 ChatResponse → 设置 lastResponse 和 token。
 * 将 assistant 消息（含 tool_use blocks）追加到 ctx。
 * 不检查工具调用、不决定下一阶段 —— 由引擎 evaluator 负责。
 *
 * @param ctx 循环上下文
 * @return 模型推理的流式分块
 */
private Flux<ChatStreamChunk> executeReason(LoopContext ctx) {
    List<ChatStreamChunk> buffer = new ArrayList<>();
    return modelPipeline.executeStream(ctx)
            .doOnNext(buffer::add)
            .concatWith(Flux.defer(() -> {
                ChatResponse resp = ModelCallPipeline.assembleResponseFromChunks(buffer);
                if (resp == null) return Flux.empty();

                ctx.setLastResponse(resp);
                if (resp.getUsage() != null) {
                    ctx.addTokens(resp.getUsage().getTotalTokens());
                }
                Msg assistantMsg = resp.getMessage();
                if (assistantMsg != null) {
                    ctx.addMessage(assistantMsg);
                }
                return Flux.empty();
            }));
}
```

关键变化：
- 删除了 `List<ToolUseBlock> tools = extractToolCalls(resp)` 及后续路由逻辑
- assistant 消息在这里统一追加（不管有无工具）
- 不再调用 `dispatchAfterIteration` 和 `executeAndContinue`

- [ ] **Step 2: 重写 executeAct — 剥离路由和 iteration++**

替换 `executeAct` 方法：

```java
/**
 * 执行行动阶段：并行执行工具调用并收集结果。
 *
 * <p>记录指标（iteration 此时尚未递增，使用 +1），执行工具，
 * 只追加 tool_result 消息（assistant 消息已由 executeReason 追加），
 * 应用 backoff。不递增 iteration、不分发 after-iteration hook、
 * 不调用 runStream —— 由 executePhase 链式进入 Observe。
 *
 * @param ctx       循环上下文
 * @param toolCalls 待执行的工具调用列表
 * @return 流式输出 chunk 序列
 */
private Flux<ChatStreamChunk> executeAct(LoopContext ctx, List<ToolUseBlock> toolCalls) {
    metrics.recordIteration(ctx.getAgentName(), ctx.getSessionId(),
            ctx.getIteration() + 1, toolCalls.size());

    List<ToolResult> results = new java.util.concurrent.CopyOnWriteArrayList<>();

    return Flux.fromIterable(toolCalls)
            .flatMap(tc -> toolOrchestrator.execute(tc, ctx)
                    .doOnNext(results::add)
                    .map(this::chunkFromToolResult))
            .onErrorResume(e -> handleToolError(e, ctx, results))
            .concatWith(Flux.defer(() -> {
                appendToolResults(ctx, results);
                return Mono.delay(Duration.ofMillis(ctx.getBackoffMs())).flux()
                        .thenMany(Flux.<ChatStreamChunk>empty());
            }));
}
```

关键变化：
- 删除了 `ctx.setIteration(ctx.getIteration() + 1)` — 移至 executeObserve
- metrics 用 `ctx.getIteration() + 1`
- 删除了 `dispatchAfterIteration` 调用
- 删除了最后的 `concatWith(Flux.defer(() -> runStream(ctx)))`

- [ ] **Step 3: 修改 appendToolResults — 只追加 tool result 消息**

将 `appendToolResults` 方法改为不再追加 assistant 消息（已由 executeReason 追加）：

```java
/**
 * 批量追加工具执行结果到上下文消息列表。
 *
 * <p>只追加 TOOL 角色的 tool result 消息。
 * assistant 消息已由 executeReason 统一追加。
 *
 * @param ctx     循环上下文
 * @param results 工具执行结果列表
 */
private void appendToolResults(LoopContext ctx, List<ToolResult> results) {
    for (ToolResult r : results) {
        String callId = r.getCallId();
        if (callId == null) continue;
        ctx.addMessage(Msg.builder(MsgRole.TOOL)
                .addToolResult(callId,
                        r.isSuccess() ? r.getContent()
                                : UI.TOOL_ERROR_PREFIX + r.getErrorMessage(),
                        !r.isSuccess())
                .build());
    }
}
```

- [ ] **Step 4: 新增 executeObserve 方法**

```java
/**
 * 执行观察阶段：递增迭代并分发 after-iteration Hook。
 *
 * <p>每次迭代恰好调用一次，由 executePhase 链式进入。
 * 触发 AFTER_ITERATION Hook → SessionPersistenceHook 持久化。
 *
 * @param ctx 循环上下文
 * @return 完成信号（Mono<Void> 转 Flux）
 */
private Flux<ChatStreamChunk> executeObserve(LoopContext ctx) {
    ctx.setIteration(ctx.getIteration() + 1);
    return dispatchAfterIteration(ctx)
            .thenMany(Flux.<ChatStreamChunk>empty());
}
```

- [ ] **Step 5: 新增 executePhase 路由中枢**

```java
/**
 * 状态机路由中枢。
 *
 * <p>根据引擎决策执行对应阶段，阶段完成后回访引擎获取下一决策，
 * 通过 concatWith + defer 实现订阅级递归（非调用栈递归）。
 * Stop 时返回空 Flux 结束递归。
 *
 * <p>介入中断检测：Reason 阶段前检查 ctx.isInterrupted()，
 * 中断时跳过模型调用但允许 Observe 完成持久化后再终止递归。
 *
 * @param decision 引擎决策
 * @param ctx      循环上下文
 * @return 流式输出 chunk 序列
 */
private Flux<ChatStreamChunk> executePhase(Decision decision, LoopContext ctx) {
    if (decision.isStop()) {
        return Flux.empty();
    }
    Phase next = decision.getNextPhase();

    // 中断时跳过推理阶段，避免不必要的模型调用
    // Observe 不受影响，确保持久化在中断前完成
    if (next.isReason() && ctx.isInterrupted()) {
        return Flux.empty();
    }

    Flux<ChatStreamChunk> phaseFlux;
    if (next.isReason()) {
        phaseFlux = executeReason(ctx);
    } else if (next.isAct()) {
        phaseFlux = executeAct(ctx, next.getToolCalls());
    } else if (next.isObserve()) {
        phaseFlux = executeObserve(ctx);
    } else {
        phaseFlux = Flux.empty();
    }
    return phaseFlux.concatWith(Flux.defer(() -> {
        Decision nextDecision = engine.evaluate(next, ctx);
        return executePhase(nextDecision, ctx);
    }));
}
```

- [ ] **Step 6: 修改 runStream — 使用 executePhase**

将 `runStream` 方法中对 `executeAndContinue` 的调用替换为 `executePhase`：

`runStream` 中第 107 行 `return executeAndContinue(d.getNextPhase(), ctx);` 改为：
```java
return executePhase(d, ctx);
```

- [ ] **Step 7: 修改 dispatchSummarizeHook — 使用 executePhase**

将 `dispatchSummarizeHook` 方法最后一行 `return executeAndContinue(Phase.reason(), ctx);` 改为：
```java
return executePhase(Decision.continue_(Phase.reason()), ctx);
```

- [ ] **Step 8: 修改 resumeToolWithArgs — 使用 executePhase(Observe)**

将 `resumeToolWithArgs` 方法末尾的 `dispatchAfterIteration` + backoff + `runStream` 替换：

替换这段代码：
```java
return toolOrchestrator.executeDirect(callParam, ctx)
        .flatMapMany(result -> {
            ...
            return Flux.just(chunk)
                    .concatWith(Flux.defer(() -> {
                        appendSingleToolResult(ctx, result.withCallId(callParam.getCallId()), callParam.getCallId());
                        return dispatchAfterIteration(ctx)
                                .thenMany(Mono.delay(Duration.ofMillis(ctx.getBackoffMs())).flux())
                                .thenMany(Flux.<ChatStreamChunk>empty());
                    }));
        })
        .concatWith(Flux.defer(() -> runStream(ctx)));
```

改为：
```java
return toolOrchestrator.executeDirect(callParam, ctx)
        .flatMapMany(result -> {
            ChatStreamChunk chunk = chunkFromToolResult(result);
            appendSingleToolResult(ctx, result.withCallId(callParam.getCallId()), callParam.getCallId());
            return Flux.just(chunk);
        })
        .concatWith(Flux.defer(() -> executePhase(Decision.continue_(Phase.observe()), ctx)));
```

注意：`appendSingleToolResult` 中也追加了 assistant 消息（`ctx.addMessage(lastMsg)`），但由于这里走的是介入恢复路径，之前的 assistant 消息可能尚未通过 executeReason 追加。保持不变，由 `appendSingleToolResult` 负责追加。

- [ ] **Step 9: 删除 executeAndContinue 方法**

删除整个 `executeAndContinue` 方法及其 Javadoc。

- [ ] **Step 10: 删除 extractToolCalls 方法**

删除 `extractToolCalls` 方法（引擎现在负责读 ToolUseBlock，不再需要此 helper）。

- [ ] **Step 11: 删除 executeAndContinue 中 Observe 分支的旧逻辑**

确认 `executeAndContinue` 已完全删除，Observe 路由现由 `executePhase` 统一处理。

- [ ] **Step 12: 运行全部现有测试**

```bash
mvn test -pl agent-core -Dtest=LoopExecutorTest,LoopExecutorInterventionTest,LoopDecisionEngineTest,LoopContextTest -DfailIfNoTests=false
```
Expected: 可能需要修复部分 Mock 行为差异，继续后续步骤修复

---

### Task 4: 更新测试用例

**Files:**
- Modify: `agent-core/src/test/java/cd/lan1akea/core/agent/loop/LoopExecutorTest.java`
- Modify: `agent-core/src/test/java/cd/lan1akea/core/agent/loop/LoopExecutorInterventionTest.java`

- [ ] **Step 1: 新增 LoopExecutorTest — 验证无工具走 Observe 并触发 AFTER_ITERATION**

在 `LoopExecutorTest.java` 末尾添加：

```java
// ============================================================
// Observe 闭环：无工具场景验证引擎终止
// ============================================================

@Test
void textOnly_shouldInvokeAfterIterationOnce() {
    when(model.streamWithTools(any(), any(), any()))
            .thenReturn(Flux.just(
                    ChatStreamChunk.builder().delta("hello")
                            .finishReason(FinishReason.STOP).build()));

    LoopContext ctx = LoopContext.builder()
            .agentName("test").messages(List.of(UserMessage.of("hi")))
            .generateOptions(GenerateOptions.defaults()).stream(true).build();

    StepVerifier.create(executor.runStream(ctx))
            .expectNextMatches(c -> "hello".equals(c.getDelta()))
            .verifyComplete();

    // 验证 AFTER_ITERATION 被调用（通过 Observe 阶段触发）
    verify(hookDispatcher, atLeastOnce()).dispatch(
            argThat(e -> e.getHookEventType() == HookEventType.AFTER_ITERATION),
            any());
    // 验证 iteration 被 Observe 递增
    assertEquals(1, ctx.getIteration());
    // 验证 complete 标记已设置（引擎评估无工具→markComplete）
    assertTrue(ctx.isComplete());
}

// ============================================================
// Observe 闭环：有工具场景验证 Observe 被调用
// ============================================================

@Test
void withTools_shouldGoThroughObserve() {
    when(model.streamWithTools(any(), any(), any()))
            .thenReturn(Flux.just(
                    ChatStreamChunk.builder().type(ChatStreamChunk.TYPE_TOOL_USE_START)
                            .toolUseId("c1").toolName("greet").build(),
                    ChatStreamChunk.builder().type(ChatStreamChunk.TYPE_TOOL_USE_DELTA)
                            .toolUseId("c1").delta("{}").build(),
                    ChatStreamChunk.builder().finishReason("tool_calls").build()))
            .thenReturn(Flux.just(
                    ChatStreamChunk.builder().delta("done")
                            .finishReason(FinishReason.STOP).build()));

    when(toolExecutor.execute(any()))
            .thenReturn(Mono.just(ToolResult.success("c1", "ok")));

    LoopContext ctx = LoopContext.builder()
            .agentName("test").messages(List.of(UserMessage.of("hi")))
            .generateOptions(GenerateOptions.defaults()).stream(true).build();

    executor.runStream(ctx).collectList().block();

    // 验证 AFTER_ITERATION 被调用至少 2 次（有工具：Act 后 Observe，无工具：Reason 后 Observe）
    verify(hookDispatcher, atLeast(2)).dispatch(
            argThat(e -> e.getHookEventType() == HookEventType.AFTER_ITERATION),
            any());
    // iteration 由 Observe 递增：每次 Act 对应一次 Observe
    assertEquals(1, ctx.getIteration());
}

// ============================================================
// Observe 闭环：iteration 递增在 Observe 而非 Act
// ============================================================

@Test
void iterationShouldBeIncrementedByObserve() {
    when(model.streamWithTools(any(), any(), any()))
            .thenReturn(Flux.just(ChatStreamChunk.builder().delta("ok")
                    .finishReason(FinishReason.STOP).build()));

    LoopContext ctx = LoopContext.builder()
            .agentName("test").messages(List.of(UserMessage.of("hi")))
            .generateOptions(GenerateOptions.defaults()).stream(true).build();

    assertEquals(0, ctx.getIteration());
    executor.runStream(ctx).collectList().block();
    assertEquals(1, ctx.getIteration()); // Observe 递增
}

// ============================================================
// Observe 闭环：Guard 检查 complete 后停止
// ============================================================

@Test
void completeFlag_shouldStopLoop() {
    when(model.streamWithTools(any(), any(), any()))
            .thenReturn(Flux.just(ChatStreamChunk.builder().delta("first")
                    .finishReason(FinishReason.STOP).build()));

    LoopContext ctx = LoopContext.builder()
            .agentName("test").messages(List.of(UserMessage.of("hi")))
            .generateOptions(GenerateOptions.defaults()).stream(true).build();

    executor.runStream(ctx).collectList().block();

    // 模型只调用一次（complete 后停止，不再调用第二次）
    verify(model, times(1)).streamWithTools(any(), any(), any());
}

// ============================================================
// Observe 闭环：中断后不调模型（executePhase 中检查 isInterrupted）
// ============================================================

@Test
void interruptedMidLoop_shouldNotCallModelAfterObserve() {
    // 预中断的 ctx → runStream 直接在 Guard 检查后进入 handleInterruptStream
    LoopContext ctx = LoopContext.builder()
            .agentName("test").messages(List.of(UserMessage.of("hi")))
            .generateOptions(GenerateOptions.defaults()).stream(true).build();
    ctx.interrupt();

    StepVerifier.create(executor.runStream(ctx))
            .expectNextMatches(c -> FinishReason.INTERRUPTED.equals(c.getFinishReason()))
            .verifyComplete();

    verify(model, never()).streamWithTools(any(), any(), any());
}
```

- [ ] **Step 2: 更新 LoopExecutorInterventionTest — 验证介入恢复走 Observe**

在 `LoopExecutorInterventionTest.java` 末尾添加：

```java
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
    ctx.setInterventionId("int_1");
    ctx.setInterventionType("TOOL_APPROVAL");
    ctx.setPausedToolArgs("{\"amount\":100}");

    executor.runStream(ctx).collectList().block();

    // 验证 AFTER_ITERATION 被触发（介入恢复后走 Observe）
    verify(hookDispatcher, atLeastOnce()).dispatch(
            argThat(e -> e.getHookEventType() == HookEventType.AFTER_ITERATION),
            any());
    // 验证 iteration 被 Observe 递增
    assertEquals(1, ctx.getIteration());
}
```

- [ ] **Step 3: 运行全部测试**

```bash
mvn test -pl agent-core -DfailIfNoTests=false
```
Expected: 全部测试通过（含所有新增用例）

- [ ] **Step 4: 提交**

```bash
git add agent-core/src/main/java/cd/lan1akea/core/agent/loop/LoopExecutor.java agent-core/src/test/java/cd/lan1akea/core/agent/loop/LoopExecutorTest.java agent-core/src/test/java/cd/lan1akea/core/agent/loop/LoopExecutorInterventionTest.java
git commit -m "feat: add executePhase routing hub, converge dispatchAfterIteration to Observe only"
```

---

### Task 5: 最终验证

- [ ] **Step 1: 全量测试**

```bash
mvn test -pl agent-core -DfailIfNoTests=false
```
Expected: 全部测试通过

- [ ] **Step 2: 确认覆盖率**

```bash
mvn jacoco:report -pl agent-core
```
检查 `LoopDecisionEngine` 和 `LoopExecutor` 覆盖率，特别是新增的 `executePhase`、`evaluateReason`、`evaluateObserve` 路径。

- [ ] **Step 3: 运行完整构建**

```bash
mvn clean test -pl agent-core,agent-bootstrap,agent-harness,agent-metrics -DfailIfNoTests=false
```
Expected: 所有模块测试通过
