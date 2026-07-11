# Loop & Hook Simplification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate 7 over-classification classes (Phase, Decision, LoopDecisionEngine, ReasoningEvent, ToolCallEvent, InterruptEvent, ErrorEvent), simplify dispatch pipeline, flatten RequestPipeline.

**Architecture:** Merge Phase/Decision/Engine routing into LoopExecutor direct recursion. Merge 4 HookEvent subclasses into HookEvent with typed accessors. Add HookDispatcher template to reduce dispatch boilerplate. Flatten RequestPipeline inner carrier classes.

**Tech Stack:** Java 17+, Reactor (Flux/Mono), JUnit 5

**Coding standards:** All methods and fields use `/**\n * desc\n */` Javadoc. String literals extracted to `CoreConstants`.

---

### Task 1: Delete Phase, Decision, LoopDecisionEngine and their tests

**Files:**
- Delete: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/Phase.java`
- Delete: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/Decision.java`
- Delete: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/LoopDecisionEngine.java`
- Delete: `agent-core/src/test/java/cd/lan1akea/core/agent/loop/PhaseDecisionTest.java`
- Delete: `agent-core/src/test/java/cd/lan1akea/core/agent/loop/LoopDecisionEngineTest.java`

- [ ] **Step 1: Delete the 5 files**

```bash
rm agent-core/src/main/java/cd/lan1akea/core/agent/loop/Phase.java
rm agent-core/src/main/java/cd/lan1akea/core/agent/loop/Decision.java
rm agent-core/src/main/java/cd/lan1akea/core/agent/loop/LoopDecisionEngine.java
rm agent-core/src/test/java/cd/lan1akea/core/agent/loop/PhaseDecisionTest.java
rm agent-core/src/test/java/cd/lan1akea/core/agent/loop/LoopDecisionEngineTest.java
```

- [ ] **Step 2: Verify compilation fails (expected)**

```bash
cd agent-core && mvn compile -q 2>&1 | head -20
```
Expected: compilation errors in `LoopExecutor.java` and test files referencing deleted classes.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "refactor: delete Phase, Decision, LoopDecisionEngine and their tests"
```

---

### Task 2: Merge ReasoningEvent into HookEvent

**Files:**
- Modify: `agent-core/src/main/java/cd/lan1akea/core/hook/HookEvent.java`
- Delete: `agent-core/src/main/java/cd/lan1akea/core/hook/ReasoningEvent.java`
- Delete: `agent-core/src/test/java/cd/lan1akea/core/hook/ReasoningEventTest.java`
- Modify: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/ModelCallPipeline.java`
- Modify: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/LoopExecutor.java`
- Modify: `agent-core/src/main/java/cd/lan1akea/core/hook/impl/MemoryEnrichmentHook.java`
- Modify: `agent-core/src/main/java/cd/lan1akea/core/hook/impl/ContextCompressionHook.java`
- Modify: `agent-core/src/main/java/cd/lan1akea/core/hook/impl/ContentFilterHook.java`
- Modify: `agent-core/src/main/java/cd/lan1akea/core/CoreConstants.java`

- [ ] **Step 1: Add Reasoning accessors and new constants to HookEvent**

In `CoreConstants.java`, add to `EventPayload` inner class:

```java
/** 消息列表（Reasoning events） */
public static final String MESSAGES = "messages";
/** 绕过模型调用的直接回复消息 */
public static final String BYPASS_MESSAGE = "bypassMessage";
```

In `HookEvent.java`, add after existing methods, before the closing `}`:

```java
/**
 * 设置当前消息列表。
 *
 * @param messages 消息列表
 */
public void setMessages(List<Msg> messages) {
    setPayload(CoreConstants.EventPayload.MESSAGES, messages);
}

/**
 * @return 消息列表，可能为 null
 */
@SuppressWarnings("unchecked")
public List<Msg> getMessages() {
    return getPayload(CoreConstants.EventPayload.MESSAGES);
}

/**
 * 设置绕过模型调用的直接回复消息。
 * 非 null 时调用方跳过模型直接返回此消息。
 *
 * @param msg 直接回复消息
 */
public void setBypassMessage(Msg msg) {
    setPayload(CoreConstants.EventPayload.BYPASS_MESSAGE, msg);
}

/**
 * @return 绕过消息，null 表示正常走模型
 */
public Msg getBypassMessage() {
    return getPayload(CoreConstants.EventPayload.BYPASS_MESSAGE);
}
```

Add import at top of HookEvent.java:
```java
import cd.lan1akea.core.CoreConstants;
import cd.lan1akea.core.message.Msg;
import java.util.List;
```

- [ ] **Step 2: Delete ReasoningEvent.java and its test**

```bash
rm agent-core/src/main/java/cd/lan1akea/core/hook/ReasoningEvent.java
rm agent-core/src/test/java/cd/lan1akea/core/hook/ReasoningEventTest.java
```

- [ ] **Step 3: Update all references from ReasoningEvent to HookEvent**

In `ModelCallPipeline.java`:
- Remove import `cd.lan1akea.core.hook.ReasoningEvent;`
- Change `ReasoningEvent pre = new ReasoningEvent(HookEventType.PRE_REASONING);` → `HookEvent pre = new HookEvent(HookEventType.PRE_REASONING);`
- Change `private Flux<ChatStreamChunk> callModelStream(LoopContext ctx, HookContext hc, ReasoningEvent pre)` → `private Flux<ChatStreamChunk> callModelStream(LoopContext ctx, HookContext hc, HookEvent pre)`
- Change `new ReasoningEvent(HookEventType.POST_REASONING)` → `new HookEvent(HookEventType.POST_REASONING)`

In `LoopExecutor.java`:
- Remove import `cd.lan1akea.core.hook.ReasoningEvent;`
- Change `ReasoningEvent event = new ReasoningEvent(HookEventType.PRE_SUMMARIZE);` → `HookEvent event = new HookEvent(HookEventType.PRE_SUMMARIZE);`

In `MemoryEnrichmentHook.java`:
- Change `if (!(event instanceof ReasoningEvent) || memory == null)` → `if (event.getMessages() == null || memory == null)`
- Change `ReasoningEvent re = (ReasoningEvent) event;` + `List<Msg> messages = re.getMessages();` → `List<Msg> messages = event.getMessages();`

In `ContextCompressionHook.java`:
- Change `if (!(event instanceof ReasoningEvent re))` → check `event.getMessages() == null`
- `List<Msg> messages = event.getMessages();`
- `re.setMessages(compressed);` → `event.setMessages(compressed);`

In `ContentFilterHook.java`:
- Change `if (event instanceof ReasoningEvent) { ReasoningEvent re = (ReasoningEvent) event; List<Msg> messages = re.getMessages();` → `List<Msg> messages = event.getMessages(); if (messages != null) {`

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: merge ReasoningEvent into HookEvent with typed accessors"
```

---

### Task 3: Merge ToolCallEvent into HookEvent

**Files:**
- Modify: `agent-core/src/main/java/cd/lan1akea/core/hook/HookEvent.java`
- Delete: `agent-core/src/main/java/cd/lan1akea/core/hook/ToolCallEvent.java`
- Modify: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/ToolCallOrchestrator.java`
- Modify: `agent-core/src/main/java/cd/lan1akea/core/hook/impl/ToolAccessHook.java`
- Modify: `agent-harness/src/main/java/cd/lan1akea/harness/hook/PermissionHook.java`
- Modify: `agent-core/src/main/java/cd/lan1akea/core/CoreConstants.java`

- [ ] **Step 1: Add Tool accessors to HookEvent**

In `CoreConstants.java`, add to `EventPayload`:

```java
/** 工具实例（ToolCallEvent） */
public static final String TOOL = "tool";
/** 工具调用上下文（ToolCallEvent） */
public static final String CALL_PARAM = "callParam";
/** 工具执行结果（ToolCallEvent） */
public static final String RESULT = "result";
```

In `HookEvent.java`, add Tool accessors:

```java
/**
 * @return 工具实例，可能为 null
 */
public Tool getTool() {
    return getPayload(CoreConstants.EventPayload.TOOL);
}

/**
 * 设置工具实例。
 *
 * @param tool 工具实例
 */
public void setTool(Tool tool) {
    setPayload(CoreConstants.EventPayload.TOOL, tool);
}

/**
 * @return 工具调用上下文，可能为 null
 */
public ToolCallContext getCallParam() {
    return getPayload(CoreConstants.EventPayload.CALL_PARAM);
}

/**
 * 设置工具调用上下文。
 *
 * @param callParam 工具调用上下文
 */
public void setCallParam(ToolCallContext callParam) {
    setPayload(CoreConstants.EventPayload.CALL_PARAM, callParam);
}

/**
 * @return 工具执行结果，可能为 null
 */
public ToolResult getResult() {
    return getPayload(CoreConstants.EventPayload.RESULT);
}

/**
 * 设置工具执行结果。
 *
 * @param result 工具执行结果
 */
public void setResult(ToolResult result) {
    setPayload(CoreConstants.EventPayload.RESULT, result);
}
```

Add imports for `Tool`, `ToolCallContext`, `ToolResult`.

- [ ] **Step 2: Delete ToolCallEvent.java**

```bash
rm agent-core/src/main/java/cd/lan1akea/core/hook/ToolCallEvent.java
```

- [ ] **Step 3: Update all references from ToolCallEvent to HookEvent**

In `ToolCallOrchestrator.java`:
- Remove import `cd.lan1akea.core.hook.ToolCallEvent;`
- Change all `new ToolCallEvent(type, ...)` → `new HookEvent(type)` + set callParam/result via setter
- Change method signatures: `ToolCallEvent event` → `HookEvent event`
- Change `((ToolCallEvent) e).setResult(result);` → `e.setResult(result);`
- Change `event.getCallParam()` uses — keep as-is (now on HookEvent)

In `ToolAccessHook.java`:
- Change `if (!(event instanceof ToolCallEvent tce))` → `if (event.getCallParam() == null)`
- Change `tce.getCallParam()` → `event.getCallParam()`

In `PermissionHook.java`:
- Change `if (!(event instanceof ToolCallEvent tce))` → `if (event.getTool() == null)`
- Change `tce.getTool()` → `event.getTool()`

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: merge ToolCallEvent into HookEvent with typed accessors"
```

---

### Task 4: Merge InterruptEvent and ErrorEvent into HookEvent

**Files:**
- Modify: `agent-core/src/main/java/cd/lan1akea/core/hook/HookEvent.java`
- Delete: `agent-core/src/main/java/cd/lan1akea/core/hook/InterruptEvent.java`
- Delete: `agent-core/src/main/java/cd/lan1akea/core/hook/ErrorEvent.java`
- Modify: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/LoopExecutor.java`
- Modify: `agent-core/src/main/java/cd/lan1akea/core/CoreConstants.java`

- [ ] **Step 1: Add Interrupt/Error accessors to HookEvent**

In `CoreConstants.java`, add to `EventPayload`:

```java
/** 中断原因（InterruptEvent） */
public static final String INTERRUPT_REASON = "interruptReason";
/** 是否已解决（InterruptEvent） */
public static final String RESOLVED = "resolved";
/** 解决结果（InterruptEvent） */
public static final String RESOLUTION = "resolution";
/** 异常对象（ErrorEvent） */
public static final String ERROR = "error";
/** 异常消息（ErrorEvent） */
public static final String ERROR_MESSAGE = "errorMessage";
/** 异常类型名（ErrorEvent） */
public static final String ERROR_TYPE = "errorType";
```

In `HookEvent.java`, add Interrupt accessors (replacing InterruptEvent):

```java
/**
 * @return 中断唯一标识
 */
public String getInterruptId() {
    return getPayload(CoreConstants.EventPayload.INTERRUPT_ID);
}

/**
 * @return 中断原因描述
 */
public String getInterruptReason() {
    return getPayload(CoreConstants.EventPayload.INTERRUPT_REASON);
}

/**
 * @return 是否已解决
 */
public boolean isResolvedInterrupt() {
    Boolean resolved = getPayload(CoreConstants.EventPayload.RESOLVED);
    return resolved != null && resolved;
}

/**
 * 标记中断为已解决。
 *
 * @param resolution 解决结果
 */
public void resolveInterrupt(Object resolution) {
    setPayload(CoreConstants.EventPayload.RESOLVED, true);
    setPayload(CoreConstants.EventPayload.RESOLUTION, resolution);
}

/**
 * @return 解决结果
 */
public Object getResolution() {
    return getPayload(CoreConstants.EventPayload.RESOLUTION);
}
```

Add Error accessors:

```java
/**
 * @return 异常对象，可能为 null
 */
public Throwable getError() {
    return getPayload(CoreConstants.EventPayload.ERROR);
}

/**
 * @return 异常消息，可能为 null
 */
public String getErrorMessage() {
    return getPayload(CoreConstants.EventPayload.ERROR_MESSAGE);
}
```

Also add constructor overloads for convenience:

```java
/**
 * 创建中断事件。
 *
 * @param reason   中断原因
 * @param toolName 触发工具名
 * @return 中断事件
 */
public static HookEvent interrupt(String reason, String toolName) {
    HookEvent e = new HookEvent(HookEventType.ON_INTERRUPT);
    e.setPayload(CoreConstants.EventPayload.INTERRUPT_ID, IdGenerator.nextIdStr());
    e.setPayload(CoreConstants.EventPayload.INTERRUPT_REASON, reason);
    e.setPayload(CoreConstants.EventPayload.TOOL_NAME, toolName);
    e.setPayload(CoreConstants.EventPayload.RESOLVED, false);
    return e;
}

/**
 * 创建错误事件。
 *
 * @param error 异常对象
 * @return 错误事件
 */
public static HookEvent error(Throwable error) {
    HookEvent e = new HookEvent(HookEventType.ON_ERROR);
    e.setPayload(CoreConstants.EventPayload.ERROR, error);
    if (error.getMessage() != null) {
        e.setPayload(CoreConstants.EventPayload.ERROR_MESSAGE, error.getMessage());
    }
    e.setPayload(CoreConstants.EventPayload.ERROR_TYPE, error.getClass().getName());
    return e;
}
```

Add import for `IdGenerator`.

- [ ] **Step 2: Delete InterruptEvent.java and ErrorEvent.java**

```bash
rm agent-core/src/main/java/cd/lan1akea/core/hook/InterruptEvent.java
rm agent-core/src/main/java/cd/lan1akea/core/hook/ErrorEvent.java
```

- [ ] **Step 3: Update LoopExecutor references**

In `LoopExecutor.java`:
- Remove `import cd.lan1akea.core.hook.InterruptEvent;`
- Change `InterruptEvent ie = new InterruptEvent(feedback != null ? feedback.getTextContent() : UI.INTERRUPT_EXTERNAL, null);` → `HookEvent ie = HookEvent.interrupt(feedback != null ? feedback.getTextContent() : UI.INTERRUPT_EXTERNAL, null);`
- Change `ie.getReason()` → `ie.getInterruptReason()`

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: merge InterruptEvent and ErrorEvent into HookEvent with static factories"
```

---

### Task 5: Rewrite LoopExecutor.runStream

**Files:**
- Modify: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/LoopExecutor.java` — full rewrite of runStream and related methods
- Modify: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/ModelCallPipeline.java` — move `buildInterruptedResponse` here or inline
- Modify: `agent-core/src/main/java/cd/lan1akea/core/CoreConstants.java`

- [ ] **Step 1: Add new constants to CoreConstants**

In `CoreConstants.UI`:

```java
/** 推理阶段跳过（被中断） */
public static final String REASON_SKIPPED_INTERRUPTED = "Reason skipped: interrupted";
```

- [ ] **Step 2: Rewrite LoopExecutor.runStream**

Replace the current `runStream`, `executePhase`, `dispatchSummarizeHook` methods. The new structure:

```java
/**
 * 启动流式 ReAct 循环（canonical 入口）。
 *
 * <p>线性流程：介入恢复 → 中断检查 → 完成检查 → 最大迭代检查 → 推理 → 行动/观察 → 递归。
 * 不再通过 Phase/Decision 状态机路由，直接在方法中顺序表达。
 *
 * @param ctx 循环上下文
 * @return 流式输出 chunk 序列
 */
public Flux<ChatStreamChunk> runStream(LoopContext ctx) {
    return Flux.defer(() -> {
        if (ctx.getInterventionState().hasPending() && !ctx.isInterrupted()) {
            return resolveInterventionEntry(ctx);
        }
        if (ctx.isInterrupted()) {
            return handleInterruptStream(ctx);
        }
        if (ctx.isComplete()) {
            return finalizeStream(ctx);
        }
        if (ctx.getIteration() >= ctx.getMaxIterations()) {
            return summarizeThenReason(ctx);
        }
        return executeReason(ctx).concatWith(Flux.defer(() -> {
            List<ToolUseBlock> tools = extractToolCalls(ctx);
            if (tools != null && !tools.isEmpty()) {
                return executeAct(ctx, tools).concatWith(executeObserve(ctx));
            }
            ctx.markComplete();
            return executeObserve(ctx);
        }));
    });
}
```

Helper methods to add:

```java
/**
 * 介入恢复入口，委托给 InterventionResolver 后根据 Action 路由。
 *
 * @param ctx 循环上下文
 * @return 恢复后的流式 chunk 序列
 */
private Flux<ChatStreamChunk> resolveInterventionEntry(LoopContext ctx) {
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

/**
 * 从 lastResponse 中提取 ToolUseBlock 列表。
 *
 * @param ctx 循环上下文
 * @return 工具调用列表，无则返回 null
 */
private List<ToolUseBlock> extractToolCalls(LoopContext ctx) {
    ChatResponse resp = ctx.getLastResponse();
    if (resp != null && resp.getMessage() != null) {
        return resp.getMessage().getToolUseBlocks();
    }
    return null;
}

/**
 * 完成流：构建 stop chunk 并返回。
 *
 * @param ctx 循环上下文
 * @return stop chunk
 */
private Flux<ChatStreamChunk> finalizeStream(LoopContext ctx) {
    Msg lastMsg = ctx.getLastResponse() != null
            ? ctx.getLastResponse().getMessage() : null;
    ChatUsage usage = ctx.getLastResponse() != null
            ? ctx.getLastResponse().getUsage() : new ChatUsage(0, 0);
    String reason = ctx.getLastResponse() != null
            ? ctx.getLastResponse().getFinishReason() : FinishReason.STOP;
    return Flux.just(new ChatResponse(lastMsg, usage, reason, ""))
            .map(resp -> ChatStreamChunk.builder()
                    .delta(resp.getMessage() != null ? resp.getMessage().getTextContent() : "")
                    .finishReason(resp.getFinishReason())
                    .build());
}

/**
 * 达到最大迭代时注入总结提示词并进入最后一轮推理。
 *
 * @param ctx 循环上下文
 * @return 总结轮次的流式 chunk 序列
 */
private Flux<ChatStreamChunk> summarizeThenReason(LoopContext ctx) {
    HookContext hc = ctx.toHookContext();
    HookEvent event = new HookEvent(HookEventType.PRE_SUMMARIZE);
    event.setMessages(ctx.getMessages());

    return hookDispatcher.dispatch(event, hc)
            .flatMapMany(r -> {
                if (r.isAbort()) {
                    return Flux.error(new HookAbortException(HookSource.HOOK, r.getAbortReason()));
                }
                if (event.getBypassMessage() != null) {
                    Msg bypass = event.getBypassMessage();
                    ctx.addMessage(bypass);
                    return Flux.just(ChatStreamChunk.of(bypass.getTextContent(), FinishReason.STOP));
                }
                ctx.addMessage(SystemMessage.of(
                        Prompt.MAX_ITERATIONS_SUMMARY + Prompt.MAX_ITERATIONS_NO_TOOLS));
                GenerateOptions opts = ctx.getGenerateOptions();
                ctx.setGenerateOptions(GenerateOptions.builder()
                        .temperature(opts.getTemperature())
                        .maxTokens(opts.getMaxTokens())
                        .toolChoice(ToolChoicePolicy.NONE)
                        .build());
                return executeReason(ctx);
            });
}
```

Update `executeObserve` to recurse:

```java
/**
 * 执行观察阶段并递归回 runStream。
 *
 * @param ctx 循环上下文
 * @return 完成信号后递归
 */
private Flux<ChatStreamChunk> executeObserve(LoopContext ctx) {
    ctx.setIteration(ctx.getIteration() + 1);
    return dispatchAfterIteration(ctx)
            .thenMany(Flux.defer(() -> runStream(ctx)));
}
```

- [ ] **Step 3: Move buildInterruptedResponse from deleted LoopDecisionEngine**

The `buildInterruptedResponse` static method was in `LoopDecisionEngine` (now deleted). Move it into `LoopExecutor` as a private static method:

```java
/**
 * 构建中断终止响应。
 *
 * @param reason 中断原因
 * @return 中断响应
 */
private static ChatResponse buildInterruptedResponse(String reason) {
    Msg msg = Msg.builder(MsgRole.ASSISTANT)
            .addText("[执行已中断: " + reason + "]")
            .putMetadata(CoreConstants.EventPayload.INTERRUPT_ID, reason)
            .build();
    return new ChatResponse(msg, new ChatUsage(0, 0), FinishReason.INTERRUPTED, "");
}
```

Update `handleInterruptStream` to use this (replace `LoopDecisionEngine.buildInterruptedResponse(...)` with `buildInterruptedResponse(...)`).

Also update `ModelCallPipeline.executeStream` where it currently calls `LoopDecisionEngine.buildInterruptedResponse(...)`. Replace with inline construction or a static import of the new method location. Better: inline it in ModelCallPipeline since it's now private in LoopExecutor:

```java
// In ModelCallPipeline, replace:
// ChatResponse ir = LoopDecisionEngine.buildInterruptedResponse(r.getInterruptReason());
// with:
if (r.isInterrupt()) {
    Msg irMsg = Msg.builder(MsgRole.ASSISTANT)
            .addText("[执行已中断: " + r.getInterruptReason() + "]")
            .putMetadata(CoreConstants.EventPayload.INTERRUPT_ID, r.getInterruptReason())
            .build();
    ChatResponse ir = new ChatResponse(irMsg, new ChatUsage(0, 0), FinishReason.INTERRUPTED, "");
    return Flux.just(chunkFromMessage(ir.getMessage(), FinishReason.INTERRUPTED));
}
```

- [ ] **Step 4: Remove dead code from LoopExecutor**

Remove `executePhase(Decision, LoopContext)` method entirely. It's been replaced by direct calls.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: rewrite LoopExecutor.runStream as direct linear recursion"
```

---

### Task 6: Flatten RequestPipeline inner classes

**Files:**
- Modify: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/RequestPipeline.java`

- [ ] **Step 1: Remove SessionLoadResult and LoadAndMessages inner classes, flatten prepareMessages**

Replace the current `prepareMessages`, `loadSessionAndHistory`, `executeAsStream` with a flattened version:

Delete the two inner classes:
- `SessionLoadResult`
- `LoadAndMessages`

Replace `executeStream` and `prepareMessages` → `executeAsStream` chain with:

```java
/**
 * 流式执行 —— canonical 实现。
 *
 * <p>完整流水线：解析上下文 → AroundHook 包裹 → 加载会话+注入系统消息 →
 * 构建 LoopContext → SessionGate 排队 → LoopExecutor.runStream。
 *
 * @param messages 用户消息列表
 * @param rtCtx    运行时上下文（可为 null，从 Reactor Context 回退）
 * @return 流式响应分块
 */
public Flux<ChatStreamChunk> executeStream(List<Msg> messages, RuntimeContext rtCtx) {
    return Flux.deferContextual(ctxView -> {
        RuntimeContext ctx = resolveContext(ctxView, rtCtx);
        return aroundHookChain.aroundCallStream(new HookEvent(null), HookContext.from(ctx, 0),
                e -> prepareAndExecute(ctx, messages)
                        .contextWrite(c -> writeContext(c, ctx)));
    });
}

/**
 * 加载会话、注入系统消息、构建 LoopContext 并执行。
 *
 * @param ctx      运行时上下文
 * @param messages 当前请求消息
 * @return 流式响应分块
 */
private Flux<ChatStreamChunk> prepareAndExecute(RuntimeContext ctx, List<Msg> messages) {
    return loadSessionAndEnrich(ctx, messages)
            .flatMapMany(enriched -> {
                LoopContext loopCtx = LoopContextFactory.create(
                        agentName, ctx, enriched, resolveOptions(), execConfig, true);
                return Flux.using(
                        () -> trackActive(loopCtx),
                        lc -> withTimeoutAndGate(lc, loopExecutor.runStream(lc)),
                        this::untrackActive);
            });
}

/**
 * 加载会话历史并注入系统消息。
 * 若有待解决介入则恢复状态到 LoopContext（由 prepareAndExecute 构建后应用）。
 *
 * @param ctx      运行时上下文
 * @param messages 当前请求消息
 * @return 合并后的消息列表 Mono
 */
private Mono<List<Msg>> loadSessionAndEnrich(RuntimeContext ctx, List<Msg> messages) {
    // delegate to existing loadSessionAndHistory logic but simplify return type
    // ... (keep existing session/checkpoint/intervention logic, return Mono<List<Msg>> directly)
}
```

The key change: instead of `SessionLoadResult` and `LoadAndMessages` carrying intermediate data, `loadSessionAndEnrich` directly applies intervention state to `LoopContext` inside `prepareAndExecute` via a simpler inline check.

Note: Keep existing `loadSessionAndHistory`, `resolveCheckpoint`, `handlePendingIntervention`, `restoreFromCheckpoint`, `loadHistory` methods but refactor them to return `Mono<List<Msg>>` instead of `SessionLoadResult`.

Add an `InterventionRecovery` simple record to carry the 3 intervention fields between session load and LoopContext:

```java
/**
 * 介入恢复数据，在会话加载时检测并应用于 LoopContext。
 */
private static class InterventionRecovery {
    final String interventionId;
    final String interventionType;
    final String pausedToolArgs;

    InterventionRecovery(String interventionId, String interventionType, String pausedToolArgs) {
        this.interventionId = interventionId;
        this.interventionType = interventionType;
        this.pausedToolArgs = pausedToolArgs;
    }
}
```

Then `loadSessionAndEnrich` returns:

```java
private Mono<EnrichedMessages> loadSessionAndEnrich(RuntimeContext ctx, List<Msg> messages) {
    // ... returns Mono.just(new EnrichedMessages(messages, recovery))
}

private static class EnrichedMessages {
    final List<Msg> messages;
    final InterventionRecovery recovery;
    // ...
}
```

Hmm, this still needs a carrier. Let me think of a simpler approach...

Actually, the simplest approach: since intervention state is only applied if `result.interventionId != null`, we can just have `prepareAndExecute` call `loadSessionAndHistory` (which returns `Mono<List<Msg>>` now) and separately handle intervention. But the intervention info comes from the same session load path...

Simplest approach: Keep a single inner record `EnrichedMessages` (replacing both `SessionLoadResult` and `LoadAndMessages`):

```java
/**
 * 会话加载 + 系统消息注入后的聚合结果。
 */
private static class EnrichedMessages {
    final List<Msg> messages;
    final String interventionId;
    final String interventionType;
    final String pausedToolArgs;

    EnrichedMessages(List<Msg> messages) {
        this(messages, null, null, null);
    }

    EnrichedMessages(List<Msg> messages, String interventionId,
                     String interventionType, String pausedToolArgs) {
        this.messages = messages;
        this.interventionId = interventionId;
        this.interventionType = interventionType;
        this.pausedToolArgs = pausedToolArgs;
    }
}
```

This replaces both `SessionLoadResult` and `LoadAndMessages` — one inner class instead of two.

- [ ] **Step 2: Commit**

```bash
git add -A
git commit -m "refactor: flatten RequestPipeline, replace two inner classes with one"
```

---

### Task 7: Add HookDispatcher withPrePostHooks template

**Files:**
- Modify: `agent-core/src/main/java/cd/lan1akea/core/hook/HookDispatcher.java`
- Modify: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/ModelCallPipeline.java`
- Modify: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/ToolCallOrchestrator.java`

- [ ] **Step 1: Add withPrePostHooks to HookDispatcher**

Add these methods to `HookDispatcher.java`:

```java
/**
 * 管线模板：dispatch pre-hook → 处理 abort/interrupt → 执行 core → fire post-hook。
 * post-hook 为 fire-and-forget 模式，不影响主流程。
 *
 * @param <T>      Flux 元素类型
 * @param preEvent pre-hook 事件
 * @param ctx      Hook 上下文
 * @param core     核心操作（在 pre-hook continue 后执行）
 * @param postType post-hook 事件类型
 * @return 核心操作的 Flux，后接 post-hook 完成信号
 */
public <T> Flux<T> dispatchAndExecuteStream(HookEvent preEvent, HookContext ctx,
                                             Function<HookEvent, Flux<T>> core,
                                             HookEventType postType) {
    return dispatch(preEvent, ctx)
            .flatMapMany(r -> {
                if (r.isAbort()) {
                    return Flux.error(new HookAbortException(
                            CoreConstants.HookSource.HOOK, r.getAbortReason()));
                }
                if (r.isInterrupt()) {
                    return Flux.empty();
                }
                if (r.isSkip()) {
                    return Flux.empty();
                }
                return core.apply(preEvent)
                        .concatWith(dispatch(new HookEvent(postType), ctx)
                                .then(Mono.<T>empty()).flux());
            });
}
```

Add import:
```java
import cd.lan1akea.core.CoreConstants;
import cd.lan1akea.core.exception.HookAbortException;
import java.util.function.Function;
import reactor.core.publisher.Flux;
```

- [ ] **Step 2: Simplify ModelCallPipeline.callModelStream using template**

The `callModelStream` method currently chains `dispatch(PRE_MODEL) → aroundHook → concatWith(POST_MODEL) → concatWith(POST_REASONING)`.

The PRE_REASONING dispatch with its abort/interrupt/bypass handling is in `executeStream` — that's too specific to template-ize easily. But the `callModelStream` internal chain can use the template:

```java
private Flux<ChatStreamChunk> callModelStream(LoopContext ctx, HookContext hc, HookEvent pre) {
    List<ToolSchema> schemas = toolRegistry.getSchemas(
            ctx.getTenantId(), ctx.getUserId(), ctx.getSessionId());

    HookEvent preModel = new HookEvent(HookEventType.PRE_MODEL_CALL);
    return hookDispatcher.dispatchAndExecuteStream(preModel, hc,
            e -> {
                final long start = System.currentTimeMillis();
                return model.streamWithTools(
                        ctx.getMessages(), schemas, ctx.getGenerateOptions())
                        .doOnNext(chunk -> {
                            if (chunk.getFinishReason() != null) {
                                long latency = System.currentTimeMillis() - start;
                                metrics.recordLlmCall(
                                        model.getModelName(), model.getProvider(),
                                        latency, 0, 0, true, null);
                            }
                        });
            },
            HookEventType.POST_MODEL_CALL)
            .concatWith(firePostReasoningHook(hc));
}
```

Note: Keep the aroundHook wrapping — that's part of `executeStream` now. Actually, aroundHook should still wrap the model call. Let me restructure.

Looking more carefully, `dispatchAndExecuteStream` handles the dispatch → abort check → core → post pattern. The aroundHook wrapping is an orthogonal concern (onion pattern). Let me keep the template simple and not try to merge aroundHook into it. The simplification from removing event subclasses alone is significant.

Let me simplify: since `ModelCallPipeline.callModelStream` has the aroundHook wrapping which is its own concern, and `dispatchAndExecuteStream` handles the pre/post dispatch, the code becomes:

```java
private Flux<ChatStreamChunk> callModelStream(LoopContext ctx, HookContext hc, HookEvent pre) {
    List<ToolSchema> schemas = toolRegistry.getSchemas(
            ctx.getTenantId(), ctx.getUserId(), ctx.getSessionId());

    HookEvent preModel = new HookEvent(HookEventType.PRE_MODEL_CALL);
    return hookDispatcher.dispatchAndExecuteStream(preModel, hc,
            e -> aroundHookChain.aroundReasoningStream(pre, hc,
                    ev -> {
                        final long start = System.currentTimeMillis();
                        return model.streamWithTools(
                                ctx.getMessages(), schemas, ctx.getGenerateOptions())
                                .doOnNext(chunk -> {
                                    if (chunk.getFinishReason() != null) {
                                        long latency = System.currentTimeMillis() - start;
                                        metrics.recordLlmCall(
                                                model.getModelName(), model.getProvider(),
                                                latency, 0, 0, true, null);
                                    }
                                });
                    }),
            HookEventType.POST_MODEL_CALL)
            .concatWith(firePostReasoningHook(hc));
}
```

- [ ] **Step 3: Simplify tool dispatch in LoopExecutor**

Where LoopExecutor calls `dispatchSummarizeHook`, it can use the template too. But that has specific bypassMessage handling which makes it tricky. Let me skip that — it's already simplified from the Phase/Decision removal.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: add HookDispatcher.dispatchAndExecuteStream template method"
```

---

### Task 8: Update all test files

**Files to modify:**
- `agent-core/src/test/java/cd/lan1akea/core/agent/loop/LoopExecutorTest.java`
- `agent-core/src/test/java/cd/lan1akea/core/agent/loop/LoopExecutorInterventionTest.java`
- `agent-core/src/test/java/cd/lan1akea/core/hook/HookSkipTest.java`
- `agent-core/src/test/java/cd/lan1akea/core/hook/impl/ContextCompressionHookTest.java`
- `agent-core/src/test/java/cd/lan1akea/core/hook/impl/HookImplTest.java`
- `agent-core/src/test/java/cd/lan1akea/core/hook/HookThreadSafetyDemoTest.java`
- `agent-core/src/test/java/cd/lan1akea/core/compaction/CompactionStrategyTest.java`
- `agent-harness/src/test/java/cd/lan1akea/harness/SdkCapabilityShowcaseTest.java`

- [ ] **Step 1: Update imports and references in all test files**

Replace all `ReasoningEvent` → `HookEvent`, `ToolCallEvent` → `HookEvent`, `InterruptEvent` → `HookEvent`, `ErrorEvent` → `HookEvent`.

Replace `instanceof ReasoningEvent re` → check `event.getMessages() != null`.
Replace `instanceof ToolCallEvent tce` → check `event.getCallParam() != null`.

Key changes:

In `HookSkipTest.java`: `new ToolCallEvent(type, param)` → `HookEvent e = new HookEvent(type); e.setCallParam(param);`

In `HookImplTest.java`: `new ReasoningEvent(type)` → `new HookEvent(type)`, `new ToolCallEvent(type, param)` → `HookEvent e = new HookEvent(type); e.setCallParam(param);`

In `ContextCompressionHookTest.java`: `new ReasoningEvent(type)` → `new HookEvent(type)`

In `CompactionStrategyTest.java`: `new ReasoningEvent(type)` → `new HookEvent(type)` with `event.setMessages(...)`

In `LoopExecutorTest.java`: Update any test helpers that use Phase/Decision/ReasoningEvent.

In `SdkCapabilityShowcaseTest.java`: `new ReasoningEvent(type)` → `new HookEvent(type)`

In `HookThreadSafetyDemoTest.java`: `new ReasoningEvent(type)` → `new HookEvent(type)`

- [ ] **Step 2: Run all tests to verify**

```bash
cd agent-core && mvn test -q 2>&1 | tail -30
```

Expected: All tests pass.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "test: update all tests for Phase/Decision/Event removal"
```

---

### Task 9: Final verification and cleanup

**Files:**
- Verify: all deleted files are gone
- Verify: no remaining references to deleted classes

- [ ] **Step 1: Verify no remaining references to deleted classes**

```bash
grep -rn "import.*\.Phase;" --include="*.java" agent-core/src/main/
grep -rn "import.*\.Decision;" --include="*.java" agent-core/src/main/
grep -rn "import.*LoopDecisionEngine" --include="*.java" agent-core/src/main/
grep -rn "import.*ReasoningEvent" --include="*.java" agent-core/src/main/
grep -rn "import.*ToolCallEvent" --include="*.java" agent-core/src/main/
grep -rn "import.*InterruptEvent" --include="*.java" agent-core/src/main/
grep -rn "import.*ErrorEvent" --include="*.java" agent-core/src/main/
```
Expected: No output for each.

- [ ] **Step 2: Run full test suite**

```bash
cd agent-core && mvn test 2>&1 | tail -20
```
Expected: BUILD SUCCESS.

- [ ] **Step 3: Run harness tests if applicable**

```bash
cd agent-harness && mvn test 2>&1 | tail -20
```
Expected: BUILD SUCCESS.

- [ ] **Step 4: Final commit**

```bash
git add -A
git commit -m "chore: final cleanup and verification after loop/hook simplification"
```
