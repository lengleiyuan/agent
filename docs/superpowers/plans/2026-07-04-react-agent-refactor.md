# ReActAgent 主链路重构 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 以流式路径为 canonical 实现，提取 Phase-Driven 状态机，从根本上消除 Mono/Flux 双轨重复。

**Architecture:** ReActAgent → RequestPipeline → LoopExecutor → { LoopDecisionEngine, ModelCallPipeline, ToolCallOrchestrator }。LoopExecutor 以显式循环替代递归 flatMap，LoopDecisionEngine 纯同步逻辑不含 Reactor。

**Tech Stack:** Java 17, Reactor (Mono/Flux), 现有 Hook/Tool/Model 体系。禁用 record/sealed/var，允许 switch。

**约束:** 允许 Breaking Change，每步独立编译通过。

---

## 文件结构

```
agent-core/src/main/java/cd/lan1akea/core/
  agent/
    ReActAgent.java              [MODIFY] 721→~120行，删除Builder，委托给RequestPipeline
    loop/
      Phase.java                 [NEW]    ~60行，循环状态枚举 + 载体类
      Decision.java              [NEW]    ~40行，决策结果类
      LoopDecisionEngine.java    [NEW]    ~50行，状态机决策引擎
      LoopExecutor.java          [NEW]    ~120行，循环执行器
      ModelCallPipeline.java     [NEW]    ~80行，推理Hook管线
      ToolCallOrchestrator.java  [NEW]    ~100行，工具调用编排
      LoopContextFactory.java    [NEW]    ~20行，上下文工厂
      RequestPipeline.java       [NEW]    ~90行，请求预处理
    config/
      AgentConfig.java           [MODIFY] 可能需要调整构造可见性

  [DELETE] agent/loop/ReActLoop.java
```

---

### Task 1: Phase 和 Decision 类型定义

**Files:**
- Create: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/Phase.java`
- Create: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/Decision.java`

#### Phase.java

```java
package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.message.ToolUseBlock;
import cd.lan1akea.core.tool.ToolResult;

import java.util.List;

/**
 * ReAct 循环阶段状态。
 * 不可变，通过静态工厂创建，通过检查方法判断类型。
 */
public final class Phase {

    public enum Type { GUARD, REASON, ACT, OBSERVE }

    private final Type type;
    private final List<ToolUseBlock> toolCalls;
    private final List<ToolResult> results;

    private Phase(Type type, List<ToolUseBlock> toolCalls, List<ToolResult> results) {
        this.type = type;
        this.toolCalls = toolCalls;
        this.results = results;
    }

    // ---- 静态工厂 ----

    public static Phase guard() {
        return new Phase(Type.GUARD, null, null);
    }

    public static Phase reason() {
        return new Phase(Type.REASON, null, null);
    }

    public static Phase act(List<ToolUseBlock> toolCalls) {
        return new Phase(Type.ACT, toolCalls, null);
    }

    public static Phase observe(List<ToolResult> results) {
        return new Phase(Type.OBSERVE, null, results);
    }

    // ---- 访问器 ----

    public Type getType() { return type; }
    public List<ToolUseBlock> getToolCalls() { return toolCalls; }
    public List<ToolResult> getResults() { return results; }

    public boolean isGuard()   { return type == Type.GUARD; }
    public boolean isReason()  { return type == Type.REASON; }
    public boolean isAct()     { return type == Type.ACT; }
    public boolean isObserve() { return type == Type.OBSERVE; }

    @Override
    public String toString() {
        switch (type) {
            case ACT: return "Act[tools=" + (toolCalls != null ? toolCalls.size() : 0) + "]";
            case OBSERVE: return "Observe[results=" + (results != null ? results.size() : 0) + "]";
            default: return type.name();
        }
    }
}
```

#### Decision.java

```java
package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.model.ChatResponse;

/**
 * 状态机决策结果。
 * 不可变，通过静态工厂创建。
 */
public final class Decision {

    private final boolean stop;
    private final Phase nextPhase;
    private final ChatResponse response;

    private Decision(boolean stop, Phase nextPhase, ChatResponse response) {
        this.stop = stop;
        this.nextPhase = nextPhase;
        this.response = response;
    }

    /** 继续循环，进入下一阶段 */
    public static Decision continue_(Phase next) {
        return new Decision(false, next, null);
    }

    /** 终止循环，返回最终响应 */
    public static Decision stop(ChatResponse resp) {
        return new Decision(true, null, resp);
    }

    public boolean isStop()              { return stop; }
    public Phase getNextPhase()          { return nextPhase; }
    public ChatResponse getResponse()    { return response; }

    @Override
    public String toString() {
        return stop ? "Stop" : "Continue(" + nextPhase + ")";
    }
}
```

- [ ] **Step 1: 创建 Phase.java 和 Decision.java**

```bash
cat > agent-core/src/main/java/cd/lan1akea/core/agent/loop/Phase.java << 'JAVAEOF'
... (code above)
JAVAEOF
cat > agent-core/src/main/java/cd/lan1akea/core/agent/loop/Decision.java << 'JAVAEOF'
... (code above)
JAVAEOF
```

- [ ] **Step 2: 编译验证**

```bash
cd agent-core && mvn compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add agent-core/src/main/java/cd/lan1akea/core/agent/loop/Phase.java \
        agent-core/src/main/java/cd/lan1akea/core/agent/loop/Decision.java
git commit -m "feat: add Phase and Decision types for ReAct state machine"
```

---

### Task 2: LoopDecisionEngine — 状态机决策引擎

**Files:**
- Create: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/LoopDecisionEngine.java`

#### LoopDecisionEngine.java

```java
package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.CoreConstants.Prompt;
import cd.lan1akea.core.CoreConstants.UI;
import cd.lan1akea.core.CoreConstants.FinishReason;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.MsgRole;
import cd.lan1akea.core.message.SystemMessage;
import cd.lan1akea.core.model.ChatResponse;
import cd.lan1akea.core.model.ChatUsage;

/**
 * ReAct 循环状态机决策引擎。
 * 纯同步逻辑，不依赖 Reactor、ChatModel、ToolExecutor。
 * 可直接单元测试。
 */
public class LoopDecisionEngine {

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
        if (current.isAct()) {
            return Decision.continue_(Phase.observe(current.toolCalls()));
        }
        if (current.isObserve()) {
            return evaluateObserve(ctx);
        }
        // Reason → caller handles post-reason tool check
        return Decision.continue_(current);
    }

    /**
     * Guard 阶段：检查最大迭代次数。
     * 中断检查在 LoopExecutor 中异步处理（需 Hook 分发）。
     */
    public Decision evaluateGuard(LoopContext ctx) {
        if (ctx.getIteration() >= ctx.getMaxIterations()) {
            ctx.addMessage(SystemMessage.of(
                    Prompt.MAX_ITERATIONS_SUMMARY + Prompt.MAX_ITERATIONS_NO_TOOLS));
            return Decision.continue_(Phase.reason());
        }
        return Decision.continue_(Phase.reason());
    }

    /**
     * Observe 阶段：递增迭代次数，回到 Guard。
     */
    private Decision evaluateObserve(LoopContext ctx) {
        ctx.setIteration(ctx.getIteration() + 1);
        return Decision.continue_(Phase.guard());
    }

    /**
     * 构建中断终止响应。
     */
    public static ChatResponse buildInterruptedResponse(String reason) {
        Msg msg = Msg.builder(MsgRole.ASSISTANT)
                .addText(UI.INTERRUPT_PREFIX + reason + UI.INTERRUPT_SUFFIX)
                .putMetadata("interruptId", reason)
                .build();
        return new ChatResponse(msg, new ChatUsage(0, 0), FinishReason.INTERRUPTED, "");
    }
}
```

- [ ] **Step 1: 创建 LoopDecisionEngine.java**

- [ ] **Step 2: 编译验证**

```bash
cd agent-core && mvn compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add agent-core/src/main/java/cd/lan1akea/core/agent/loop/LoopDecisionEngine.java
git commit -m "feat: add LoopDecisionEngine for pure-logic state transitions"
```

---

### Task 3: ToolCallOrchestrator — 工具调用编排

**Files:**
- Create: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/ToolCallOrchestrator.java`

从 ReActLoop.executeSingleTool() 提取，拆为 4 步：buildContext → dispatchPreHook → executeWithApproval → dispatchPostHook。

#### ToolCallOrchestrator.java

```java
package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.CoreConstants.EventPayload;
import cd.lan1akea.core.CoreConstants.UI;
import cd.lan1akea.core.approval.ApprovalStore;
import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.message.ToolUseBlock;
import cd.lan1akea.core.tool.*;

import reactor.core.publisher.Mono;

/**
 * 工具调用编排器。
 * 将单次工具调用拆为: 构建上下文 → PRE Hook → 审批/执行 → POST Hook。
 */
public class ToolCallOrchestrator {

    private final ToolExecutor toolExecutor;
    private final ToolRegistry toolRegistry;
    private final HookDispatcher hookDispatcher;
    private final AroundHookChain aroundHookChain;

    public ToolCallOrchestrator(ToolExecutor toolExecutor, ToolRegistry toolRegistry,
                                 HookDispatcher hookDispatcher, AroundHookChain aroundHookChain) {
        this.toolExecutor = toolExecutor;
        this.toolRegistry = toolRegistry;
        this.hookDispatcher = hookDispatcher;
        this.aroundHookChain = aroundHookChain;
    }

    /**
     * 执行单个工具调用，返回含 callId 的结果。
     */
    public Mono<ToolResult> execute(ToolUseBlock tc, LoopContext ctx) {
        HookContext hc = buildHookContext(ctx);
        ToolCallContext param = buildContext(tc, ctx);
        ToolCallEvent event = new ToolCallEvent(HookEventType.PRE_TOOL_CALL, param);
        event.setTool(toolRegistry.getForContext(
                ctx.getTenantId(), ctx.getUserId(), ctx.getSessionId(), tc.getName()));

        return dispatchPreHook(event, hc)
                .flatMap(preResult -> {
                    if (preResult != null) return Mono.just(preResult);
                    return executeWithApproval(param, event, hc, ctx)
                            .flatMap(result -> dispatchPostHook(param, result, hc));
                })
                .map(r -> r.withCallId(param.getCallId()));
    }

    // ---- Step 1: 构建上下文 ----

    private ToolCallContext buildContext(ToolUseBlock tc, LoopContext ctx) {
        return ToolCallContext.builder()
                .callId(tc.getId())
                .toolName(tc.getName())
                .arguments(tc.getArgumentsMap())
                .tenantId(ctx.getTenantId())
                .userId(ctx.getUserId())
                .sessionId(ctx.getSessionId())
                .attributes(ctx.getAttributes())
                .build();
    }

    // ---- Step 2: PRE Hook 分发 ----

    private Mono<ToolResult> dispatchPreHook(ToolCallEvent event, HookContext hc) {
        return hookDispatcher.dispatch(event, hc)
                .flatMap(r -> {
                    if (r.isAbort()) {
                        return Mono.just(ToolResult.failure(UI.TOOL_BLOCKED + r.getAbortReason()));
                    }
                    if (r.isSkip()) {
                        ToolResult skipped = ToolResult.success(
                                UI.TOOL_SKIPPED_PREFIX
                                        + (r.getSkipReason() != null ? r.getSkipReason() : UI.TOOL_SKIPPED_DEFAULT));
                        ToolCallEvent postSkip = new ToolCallEvent(HookEventType.POST_TOOL_CALL,
                                event.getCallParam(), skipped);
                        return hookDispatcher.dispatch(postSkip, hc).thenReturn(skipped);
                    }
                    return Mono.empty(); // continue
                });
    }

    // ---- Step 3: 执行 + 审批处理 ----

    private Mono<ToolResult> executeWithApproval(ToolCallContext param, ToolCallEvent event,
                                                   HookContext hc, LoopContext ctx) {
        return aroundHookChain.aroundToolCall(event, hc,
                        (HookEvent e) -> toolExecutor.execute(param)
                                .map(result -> {
                                    e.setPayload("tool_result", result);
                                    ((ToolCallEvent) e).setResult(result);
                                    return e;
                                }))
                .flatMap(e -> {
                    ToolResult result = e.getPayload("tool_result");
                    if (result == null && e instanceof ToolCallEvent) {
                        ToolCallEvent tce = (ToolCallEvent) e;
                        result = tce.getResult();
                    }
                    return Mono.justOrEmpty(result);
                })
                .onErrorResume(ToolSuspendException.class, e ->
                        handleSuspension(param, event, hc, ctx, e));
    }

    // ---- 审批/挂起处理 ----

    private Mono<ToolResult> handleSuspension(ToolCallContext param, ToolCallEvent event,
                                                HookContext hc, LoopContext ctx,
                                                ToolSuspendException e) {
        ApprovalStore approvalStore = toolExecutor.getApprovalStore();
        if (!param.isApproved() && approvalStore != null && event.getTool() != null) {
            String sessionId = ctx.getSessionId();
            if (sessionId != null && approvalStore.isApproved(sessionId, e.getBypassKey())) {
                param.setApproved(true);
                return toolExecutor.execute(param);
            }
        }
        InterruptEvent ie = new InterruptEvent(e.getQuestion(), param.getToolName());
        ie.setPayload(EventPayload.ARGUMENTS, param.getArgumentsMap());
        ie.setPayload(EventPayload.RECENT_MESSAGES, ctx.getMessages());
        if (event.getTool() != null) {
            ie.setPayload(EventPayload.TOOL_DESCRIPTION, event.getTool().getDescription());
            ie.setPayload(EventPayload.RISK_LEVEL, event.getTool().getRiskLevel());
        }
        return hookDispatcher.dispatch(ie, hc)
                .flatMap(ir -> {
                    if (ir.isAbort()) {
                        return Mono.just(ToolResult.failure(UI.APPROVAL_DENIED));
                    }
                    ctx.interrupt();
                    return Mono.just(ToolResult.failure(UI.APPROVAL_WAITING + e.getQuestion()));
                });
    }

    // ---- Step 4: POST Hook 分发 ----

    private Mono<ToolResult> dispatchPostHook(ToolCallContext param, ToolResult result, HookContext hc) {
        ToolCallEvent post = new ToolCallEvent(HookEventType.POST_TOOL_CALL, param, result);
        return hookDispatcher.dispatch(post, hc).thenReturn(result);
    }

    private HookContext buildHookContext(LoopContext ctx) {
        return new HookContext(ctx.getAgentName(), ctx.getRequestId(),
                ctx.getTenantId(), ctx.getSessionId(),
                ctx.getUserId(), ctx.getIteration(),
                java.util.List.of(), ctx.getAttributes());
    }
}
```

- [ ] **Step 1: 创建 ToolCallOrchestrator.java**

- [ ] **Step 2: 编译验证**

```bash
cd agent-core && mvn compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add agent-core/src/main/java/cd/lan1akea/core/agent/loop/ToolCallOrchestrator.java
git commit -m "feat: add ToolCallOrchestrator extracted from executeSingleTool"
```

---

### Task 4: ModelCallPipeline — 推理 Hook 管线

**Files:**
- Create: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/ModelCallPipeline.java`

从 ReActLoop.reasoningStream() 提取，流式为 canonical，非流式派生。

#### ModelCallPipeline.java

```java
package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.CoreConstants.EventPayload;
import cd.lan1akea.core.CoreConstants.FinishReason;
import cd.lan1akea.core.exception.HookAbortException;
import cd.lan1akea.core.CoreConstants.HookSource;
import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.metrics.AgentMetrics;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.tool.ToolRegistry;
import cd.lan1akea.core.tool.ToolSchema;
import cd.lan1akea.core.util.JsonUtils;
import cd.lan1akea.core.message.*;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 推理 Hook 管线。
 * PRE_REASONING → PRE_MODEL → [aroundHook] model.stream → POST_MODEL → POST_REASONING
 * 流式为 canonical 实现，非流式由此派生。
 */
public class ModelCallPipeline {

    private final ChatModel model;
    private final HookDispatcher hookDispatcher;
    private final ToolRegistry toolRegistry;
    private final AroundHookChain aroundHookChain;
    private final AgentMetrics metrics;

    public ModelCallPipeline(ChatModel model, HookDispatcher hookDispatcher,
                              ToolRegistry toolRegistry, AroundHookChain aroundHookChain,
                              AgentMetrics metrics) {
        this.model = model;
        this.hookDispatcher = hookDispatcher;
        this.toolRegistry = toolRegistry;
        this.aroundHookChain = aroundHookChain;
        this.metrics = metrics;
    }

    /** 流式推理 — canonical */
    public Flux<ChatStreamChunk> executeStream(LoopContext ctx) {
        HookContext hc = buildHookContext(ctx);
        ReasoningEvent pre = new ReasoningEvent(HookEventType.PRE_REASONING);

        return hookDispatcher.dispatch(pre, hc)
                .flatMapMany(r -> {
                    if (r.isAbort()) {
                        return Flux.error(new HookAbortException(HookSource.HOOK, r.getAbortReason()));
                    }
                    if (r.isInterrupt()) {
                        ChatResponse ir = LoopDecisionEngine.buildInterruptedResponse(
                                r.getInterruptReason());
                        return Flux.just(chunkFromMessage(ir.getMessage(), FinishReason.INTERRUPTED));
                    }
                    if (pre.getBypassMessage() != null) {
                        String text = pre.getBypassMessage().getTextContent();
                        return Flux.just(chunkFromText(text != null ? text : "", FinishReason.STOP));
                    }
                    return callModelStream(ctx, hc, pre);
                });
    }

    /** 非流式推理 — 从流式派生 */
    public Mono<ChatResponse> execute(LoopContext ctx) {
        return executeStream(ctx).collectList().map(ModelCallPipeline::assembleResponseFromChunks);
    }

    // ---- 模型调用 ----

    private Flux<ChatStreamChunk> callModelStream(LoopContext ctx, HookContext hc, ReasoningEvent pre) {
        List<ToolSchema> schemas = toolRegistry.getSchemas(
                ctx.getTenantId(), ctx.getUserId(), ctx.getSessionId());

        return hookDispatcher.dispatch(new HookEvent(HookEventType.PRE_MODEL_CALL), hc)
                .flatMapMany(mr -> {
                    if (mr.isAbort()) {
                        return Flux.error(new HookAbortException(HookSource.MODEL, mr.getAbortReason()));
                    }
                    return aroundHookChain.aroundReasoningStream(pre, hc,
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
                            });
                })
                .concatWith(firePostModelHook(hc))
                .concatWith(firePostReasoningHook(hc));
    }

    private Flux<ChatStreamChunk> firePostModelHook(HookContext hc) {
        return hookDispatcher.dispatch(new HookEvent(HookEventType.POST_MODEL_CALL), hc)
                .then(Mono.<ChatStreamChunk>empty()).flux();
    }

    private Flux<ChatStreamChunk> firePostReasoningHook(HookContext hc) {
        return hookDispatcher.dispatch(new ReasoningEvent(HookEventType.POST_REASONING), hc)
                .then(Mono.<ChatStreamChunk>empty()).flux();
    }

    // ---- Chunk 组装 ----

    public static ChatResponse assembleResponseFromChunks(List<ChatStreamChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return null;

        StringBuilder text = new StringBuilder();
        Map<String, String> toolArgs = new LinkedHashMap<>();
        Map<String, String> toolNames = new LinkedHashMap<>();

        for (ChatStreamChunk chunk : chunks) {
            if (chunk.getDelta() != null && ChatStreamChunk.TYPE_TEXT.equals(chunk.getType())) {
                text.append(chunk.getDelta());
            }
            if (ChatStreamChunk.TYPE_TOOL_USE_START.equals(chunk.getType())
                    && chunk.getToolUseId() != null) {
                toolNames.put(chunk.getToolUseId(),
                        chunk.getToolName() != null ? chunk.getToolName() : "");
                toolArgs.put(chunk.getToolUseId(), "");
            }
            if (ChatStreamChunk.TYPE_TOOL_USE_DELTA.equals(chunk.getType())
                    && chunk.getToolUseId() != null && chunk.getDelta() != null) {
                toolArgs.merge(chunk.getToolUseId(), chunk.getDelta(), String::concat);
            }
        }

        String finishReason = FinishReason.COMPLETED;
        for (int i = chunks.size() - 1; i >= 0; i--) {
            if (chunks.get(i).getFinishReason() != null) {
                finishReason = chunks.get(i).getFinishReason();
                break;
            }
        }

        List<ContentBlock> blocks = new ArrayList<>();
        if (text.length() > 0) blocks.add(new TextBlock(text.toString()));
        for (Map.Entry<String, String> e : toolArgs.entrySet()) {
            String id = e.getKey();
            blocks.add(new ToolUseBlock(id, toolNames.getOrDefault(id, ""),
                    JsonUtils.repairJson(e.getValue())));
        }

        Msg msg = new AssistantMessage(blocks, null);
        return new ChatResponse(msg, new ChatUsage(0, 0), finishReason, null);
    }

    // ---- 工具方法 ----

    private HookContext buildHookContext(LoopContext ctx) {
        return new HookContext(ctx.getAgentName(), ctx.getRequestId(),
                ctx.getTenantId(), ctx.getSessionId(),
                ctx.getUserId(), ctx.getIteration(),
                java.util.List.of(), ctx.getAttributes());
    }

    private static ChatStreamChunk chunkFromMessage(Msg msg, String finishReason) {
        return ChatStreamChunk.builder()
                .delta(msg.getTextContent())
                .finishReason(finishReason)
                .build();
    }

    private static ChatStreamChunk chunkFromText(String text, String finishReason) {
        return ChatStreamChunk.builder()
                .delta(text)
                .finishReason(finishReason)
                .build();
    }
}
```

- [ ] **Step 1: 创建 ModelCallPipeline.java**

- [ ] **Step 2: 编译验证**

```bash
cd agent-core && mvn compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add agent-core/src/main/java/cd/lan1akea/core/agent/loop/ModelCallPipeline.java
git commit -m "feat: add ModelCallPipeline extracted from reasoning phase"
```

---

### Task 5: LoopExecutor — 循环执行器

**Files:**
- Create: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/LoopExecutor.java`

组合 LoopDecisionEngine + ModelCallPipeline + ToolCallOrchestrator。
替代 ReActLoop，流式 canonical，非流式派生。显式 if/else 替代递归 flatMap。

#### LoopExecutor.java

关键设计：`runStream()` 入口处理 Guard + 中断，`executeAndContinue()` 链式推进 Reason → Act → 尾递归。

```
runStream(ctx)
  ├── [中断?] → handleInterruptStream
  ├── [Guard] → engine.evaluate(Phase.guard(), ctx)
  │     ├── maxIterations → 注入 summary prompt → Continue(Reason)
  │     └── 正常 → Continue(Reason)
  └── executeAndContinue(Reason, ctx)
        ├── [Reason] → modelPipeline.executeStream(ctx)
        │     ├── 无工具 → addMessage + dispatchAfterIteration → STOP
        │     └── 有工具 → executeAndContinue(Act, ctx)  // 链式推进
        ├── [Act] → 逐个执行 toolOrchestrator.execute()
        │     └── 收集结果 → appendToolResults → dispatchAfterIteration → backoff
        │     └── runStream(ctx)  // 尾递归回到 Guard
        └── [Observe] → dispatchAfterIteration → runStream(ctx)
```

```java
package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.CoreConstants.EventPayload;
import cd.lan1akea.core.CoreConstants.FinishReason;
import cd.lan1akea.core.CoreConstants.Logs;
import cd.lan1akea.core.CoreConstants.UI;
import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.message.*;
import cd.lan1akea.core.metrics.AgentMetrics;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.tool.*;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * ReAct 循环执行器。
 * 以显式循环替代递归 flatMap，流式为 canonical。
 *
 * 流程: runStream(Guard) → executeAndContinue(Reason)
 *       → [无工具: STOP] [有工具: executeAndContinue(Act)]
 *       → [Act: 执行+收集 → runStream(Guard) 尾递归]
 */
public class LoopExecutor {

    private static final Logger log = Logger.getLogger(LoopExecutor.class.getName());

    private final LoopDecisionEngine engine;
    private final ModelCallPipeline modelPipeline;
    private final ToolCallOrchestrator toolOrchestrator;
    private final HookDispatcher hookDispatcher;
    private final AgentMetrics metrics;

    public LoopExecutor(LoopDecisionEngine engine, ModelCallPipeline modelPipeline,
                         ToolCallOrchestrator toolOrchestrator, HookDispatcher hookDispatcher,
                         AgentMetrics metrics) {
        this.engine = engine;
        this.modelPipeline = modelPipeline;
        this.toolOrchestrator = toolOrchestrator;
        this.hookDispatcher = hookDispatcher;
        this.metrics = metrics;
    }

    // ============================================================
    // 流式 — canonical
    // ============================================================

    /**
     * 入口: Guard 检查 + 中断检查.
     */
    public Flux<ChatStreamChunk> runStream(LoopContext ctx) {
        return Flux.defer(() -> {
            if (ctx.isInterrupted()) {
                return handleInterruptStream(ctx);
            }
            Decision d = engine.evaluate(Phase.guard(), ctx);
            if (d.isStop()) {
                return Flux.just(chunkFromResponse(d.getResponse()));
            }
            return executeAndContinue(d.getNextPhase(), ctx);
        });
    }

    /**
     * 阶段推进: Reason → (Act) → 尾递归 runStream.
     * 不通过 Decision 返回值推进（避免丢失中间态），
     * 而是直接在方法内链式调用。
     */
    private Flux<ChatStreamChunk> executeAndContinue(Phase phase, LoopContext ctx) {
        if (phase.isReason()) {
            return executeReason(ctx);
        }
        if (phase.isAct()) {
            return executeAct(ctx, phase.getToolCalls());
        }
        if (phase.isObserve()) {
            return dispatchAfterIteration(ctx)
                    .thenMany(Flux.defer(() -> runStream(ctx)));
        }
        return Flux.empty();
    }

    // ---- Reason ----

    private Flux<ChatStreamChunk> executeReason(LoopContext ctx) {
        // 缓冲收集分块用于后续组装
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

                    List<ToolUseBlock> tools = extractToolCalls(resp);
                    if (tools.isEmpty()) {
                        // 无工具 → 保存消息 → 分发 AFTER_ITERATION → STOP
                        Msg lastMsg = resp.getMessage();
                        if (lastMsg != null) ctx.addMessage(lastMsg);
                        return dispatchAfterIteration(ctx).thenMany(Flux.empty());
                    }
                    // 有工具 → 链式进入 Act
                    return executeAndContinue(Phase.act(tools), ctx);
                }));
    }

    // ---- Act ----

    private Flux<ChatStreamChunk> executeAct(LoopContext ctx, List<ToolUseBlock> toolCalls) {
        ctx.setIteration(ctx.getIteration() + 1);
        metrics.recordIteration(ctx.getAgentName(), ctx.getSessionId(),
                ctx.getIteration(), toolCalls.size());

        List<ToolResult> results = new ArrayList<>();

        return Flux.fromIterable(toolCalls)
                .flatMap(tc -> toolOrchestrator.execute(tc, ctx)
                        .doOnNext(results::add)
                        .map(result -> {
                            String content = result.isSuccess()
                                    ? result.getContent()
                                    : UI.TOOL_ERROR_PREFIX + result.getErrorMessage();
                            return ChatStreamChunk.builder()
                                    .delta(content).type(ChatStreamChunk.TYPE_TEXT).build();
                        }))
                .concatWith(Flux.defer(() -> {
                    appendToolResults(ctx, results);
                    return dispatchAfterIteration(ctx)
                            .thenMany(Mono.delay(Duration.ofMillis(ctx.getBackoffMs())).flux())
                            .thenMany(Flux.<ChatStreamChunk>empty());
                }))
                .concatWith(Flux.defer(() -> runStream(ctx))); // 尾递归回到 Guard
    }

    // ============================================================
    // 非流式 — 从流式派生
    // ============================================================

    public Mono<ChatResponse> run(LoopContext ctx) {
        return runStream(ctx)
                .collectList()
                .map(ModelCallPipeline::assembleResponseFromChunks);
    }

    // ============================================================
    // 中断处理
    // ============================================================

    private Flux<ChatStreamChunk> handleInterruptStream(LoopContext ctx) {
        Msg feedback = ctx.getFeedbackMsg();
        HookContext hc = buildHookContext(ctx);
        InterruptEvent ie = new InterruptEvent(
                feedback != null ? feedback.getTextContent() : UI.INTERRUPT_EXTERNAL, null);

        return hookDispatcher.dispatch(ie, hc)
                .flatMapMany(r -> {
                    if (r.isAbort()) {
                        return Flux.just(chunkFromText(
                                UI.INTERRUPT_STREAM_PREFIX + r.getAbortReason()
                                        + UI.INTERRUPT_SUFFIX,
                                FinishReason.INTERRUPTED));
                    }
                    if (feedback != null) {
                        ctx.addMessage(feedback);
                        ctx.clearInterrupt();
                        return runStream(ctx);
                    }
                    String reason = ctx.getLastResponse() != null
                            ? ctx.getLastResponse().getMessage().getTextContent()
                            : UI.INTERRUPT_EXEC;
                    return Flux.just(chunkFromText(reason, "interrupted"));
                });
    }

    // ============================================================
    // 工具方法
    // ============================================================

    private void appendToolResults(LoopContext ctx, List<ToolResult> results) {
        Msg lastMsg = ctx.getLastResponse() != null
                ? ctx.getLastResponse().getMessage() : null;
        if (lastMsg == null) return;
        ctx.addMessage(lastMsg);

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

    private Mono<Void> dispatchAfterIteration(LoopContext ctx) {
        HookContext hc = buildHookContext(ctx);
        HookEvent event = new HookEvent(HookEventType.AFTER_ITERATION);
        event.setPayload(EventPayload.LOOP_CONTEXT, ctx);
        return hookDispatcher.dispatch(event, hc)
                .onErrorResume(e -> {
                    log.warning(Logs.AFTER_ITERATION_FAILED
                            + ctx.getRequestId() + Logs.ERR_DETAIL + e.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    private List<ToolUseBlock> extractToolCalls(ChatResponse response) {
        if (response == null || response.getMessage() == null) return List.of();
        List<ToolUseBlock> blocks = response.getMessage().getToolUseBlocks();
        return blocks != null ? blocks : List.of();
    }

    private HookContext buildHookContext(LoopContext ctx) {
        return new HookContext(ctx.getAgentName(), ctx.getRequestId(),
                ctx.getTenantId(), ctx.getSessionId(),
                ctx.getUserId(), ctx.getIteration(),
                java.util.List.of(), ctx.getAttributes());
    }

    private static ChatStreamChunk chunkFromResponse(ChatResponse resp) {
        return ChatStreamChunk.builder()
                .delta(resp.getMessage() != null ? resp.getMessage().getTextContent() : "")
                .finishReason(resp.getFinishReason())
                .build();
    }

    private static ChatStreamChunk chunkFromText(String text, String finishReason) {
        return ChatStreamChunk.builder()
                .delta(text)
                .finishReason(finishReason)
                .build();
    }
}
```

- [ ] **Step 1: 创建 LoopExecutor.java**

- [ ] **Step 2: 编译验证**

```bash
cd agent-core && mvn compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add agent-core/src/main/java/cd/lan1akea/core/agent/loop/LoopExecutor.java
git commit -m "feat: add LoopExecutor with explicit phase-driven loop"
```

---

### Task 6: LoopContextFactory + RequestPipeline

**Files:**
- Create: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/LoopContextFactory.java`
- Create: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/RequestPipeline.java`

#### LoopContextFactory.java

```java
package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.agent.config.AgentExecutionConfig;
import cd.lan1akea.core.context.RuntimeContext;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.model.GenerateOptions;

import java.util.List;

/**
 * LoopContext 统一工厂。
 * 替代 doChat/doStream 中重复的 7 行 builder 链。
 */
public final class LoopContextFactory {

    private LoopContextFactory() {}

    public static LoopContext create(String agentName, RuntimeContext ctx,
                                      List<Msg> messages, GenerateOptions opts,
                                      AgentExecutionConfig execConfig, boolean stream) {
        return LoopContext.builder()
                .agentName(agentName)
                .fromRuntimeContext(ctx)
                .messages(messages)
                .generateOptions(opts)
                .maxIterations(execConfig.getMaxIterations())
                .backoffMs(execConfig.getIterationBackoffMs())
                .stream(stream)
                .build();
    }
}
```

#### RequestPipeline.java

```java
package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.CoreConstants.Defaults;
import cd.lan1akea.core.CoreConstants.RuntimeCtx;
import cd.lan1akea.core.agent.config.AgentExecutionConfig;
import cd.lan1akea.core.context.RuntimeContext;
import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.SystemMessage;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.session.*;
import cd.lan1akea.core.state.AgentStateStore;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 请求预处理管线。
 * resolveContext → aroundCall → loadSession → injectSystemMessage → buildLoopCtx → execute
 * 替代 ReActAgent.doChat/doStream 的重复逻辑。
 */
public class RequestPipeline {

    private final LoopExecutor loopExecutor;
    private final AgentStateStore stateStore;
    private final AroundHookChain aroundHookChain;
    private final AgentExecutionConfig execConfig;
    private final String agentName;
    private final String systemMessage;
    private final ConcurrentHashMap<String, LoopContext> activeRequests;

    public RequestPipeline(LoopExecutor loopExecutor, AgentStateStore stateStore,
                            AroundHookChain aroundHookChain, AgentExecutionConfig execConfig,
                            String agentName, String systemMessage) {
        this.loopExecutor = loopExecutor;
        this.stateStore = stateStore;
        this.aroundHookChain = aroundHookChain;
        this.execConfig = execConfig;
        this.agentName = agentName;
        this.systemMessage = systemMessage;
        this.activeRequests = new ConcurrentHashMap<>();
    }

    /** 流式执行 — canonical */
    public Flux<ChatStreamChunk> executeStream(List<Msg> messages, RuntimeContext rtCtx) {
        return Flux.deferContextual(ctxView -> {
            RuntimeContext ctx = resolveContext(ctxView, rtCtx);
            HookContext callHc = HookContext.from(ctx, 0);
            GenerateOptions opts = resolveOptions();

            return aroundHookChain.aroundCallStream(new HookEvent(null), callHc,
                    e -> loadSessionAndHistory(ctx, messages)
                            .flatMapMany(msgs -> injectSystemMessage(msgs).flatMapMany(Flux::just))
                            .concatMap(m -> {
                                LoopContext loopCtx = LoopContextFactory.create(
                                        agentName, ctx, m, opts, execConfig, true);
                                activeRequests.put(loopCtx.getRequestId(), loopCtx);
                                Flux<ChatStreamChunk> stream = loopExecutor.runStream(loopCtx)
                                        .doFinally(s -> activeRequests.remove(loopCtx.getRequestId()));
                                long timeout = execConfig.getTotalTimeoutMs();
                                if (timeout > 0) stream = stream.timeout(Duration.ofMillis(timeout));
                                return stream;
                            })
                            .contextWrite(c -> writeContext(c, ctx)));
        });
    }

    /** 非流式执行 — 从流式派生 */
    public Mono<ChatResponse> execute(List<Msg> messages, RuntimeContext rtCtx) {
        return executeStream(messages, rtCtx)
                .collectList()
                .map(ModelCallPipeline::assembleResponseFromChunks)
                .switchIfEmpty(Mono.just(new ChatResponse(
                        null, new ChatUsage(0, 0), "empty", "")));
    }

    /** 中断所有活跃请求 */
    public void interrupt() {
        for (LoopContext ctx : activeRequests.values()) {
            ctx.interrupt();
        }
    }

    public void interruptBySession(String sessionId) {
        for (LoopContext ctx : activeRequests.values()) {
            if (sessionId != null && sessionId.equals(ctx.getSessionId())) {
                ctx.interrupt();
            }
        }
    }

    public boolean isRunning() {
        return !activeRequests.isEmpty();
    }

    // ---- 上下文解析 ----

    private RuntimeContext resolveContext(reactor.util.context.ContextView ctxView,
                                           RuntimeContext rtCtx) {
        if (rtCtx != null) return rtCtx;
        return new RuntimeContext(
                ctxView.getOrDefault(RuntimeCtx.REQUEST_ID, null),
                ctxView.getOrDefault(RuntimeCtx.TENANT_ID, null),
                ctxView.getOrDefault(RuntimeCtx.USER_ID, null),
                ctxView.getOrDefault(RuntimeCtx.SESSION_ID, null),
                agentName,
                ctxView.getOrDefault(RuntimeCtx.ATTRIBUTES, null));
    }

    private reactor.util.context.Context writeContext(
            reactor.util.context.Context c, RuntimeContext ctx) {
        if (ctx.getTenantId() != null) c = c.put(RuntimeCtx.TENANT_ID, ctx.getTenantId());
        if (ctx.getUserId() != null) c = c.put(RuntimeCtx.USER_ID, ctx.getUserId());
        if (ctx.getSessionId() != null) c = c.put(RuntimeCtx.SESSION_ID, ctx.getSessionId());
        if (!ctx.getAttributes().isEmpty()) c = c.put(RuntimeCtx.ATTRIBUTES, ctx.getAttributes());
        return c;
    }

    // ---- Session / History ----

    private Mono<List<Msg>> loadSessionAndHistory(RuntimeContext ctx, List<Msg> messages) {
        String sessionId = ctx.getSessionId();
        if (sessionId == null || stateStore == null) return Mono.just(messages);

        SessionId sid = new SessionId(sessionId);
        String tenantId = ctx.getTenantId() != null ? ctx.getTenantId() : Defaults.TENANT;

        return stateStore.findById(sid)
                .flatMap(session -> stateStore.loadLatestCheckpoint(sessionId)
                        .flatMap(checkpoint -> {
                            if (checkpoint.isShutdownInterrupted()) {
                                checkpoint.setShutdownInterrupted(false);
                                return stateStore.saveCheckpoint(checkpoint).thenReturn(checkpoint);
                            }
                            return Mono.just(checkpoint);
                        })
                        .flatMap(checkpoint -> {
                            List<Msg> restored = checkpoint.getMessages();
                            if (restored != null && !restored.isEmpty()) {
                                restored.addAll(messages);
                                return Mono.just(restored);
                            }
                            return loadHistory(sessionId, messages);
                        })
                        .switchIfEmpty(loadHistory(sessionId, messages)))
                .switchIfEmpty(
                        stateStore.create(new Session(sid, tenantId, agentName,
                                        SessionState.ACTIVE, null, null, null))
                                .then(Mono.just(messages)));
    }

    private Mono<List<Msg>> loadHistory(String sessionId, List<Msg> messages) {
        return stateStore.getHistory(new SessionId(sessionId))
                .collectList()
                .map(historyMsgs -> {
                    List<Msg> all = new ArrayList<>(historyMsgs);
                    all.addAll(messages);
                    return all;
                })
                .defaultIfEmpty(messages);
    }

    private Mono<List<Msg>> injectSystemMessage(List<Msg> messages) {
        String sysMsg = systemMessage;
        if (sysMsg != null && !sysMsg.isBlank()) {
            List<Msg> enriched = new ArrayList<>();
            enriched.add(SystemMessage.of(sysMsg));
            enriched.addAll(messages);
            return Mono.just(enriched);
        }
        return Mono.just(messages);
    }

    private GenerateOptions resolveOptions() {
        return GenerateOptions.builder()
                .temperature(execConfig.getTemperature())
                .maxTokens(execConfig.getMaxTokens())
                .toolChoice(execConfig.getToolChoice())
                .build();
    }
}
```

- [ ] **Step 1: 创建 LoopContextFactory.java 和 RequestPipeline.java**

- [ ] **Step 2: 编译验证**

```bash
cd agent-core && mvn compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add agent-core/src/main/java/cd/lan1akea/core/agent/loop/LoopContextFactory.java \
        agent-core/src/main/java/cd/lan1akea/core/agent/loop/RequestPipeline.java
git commit -m "feat: add LoopContextFactory and RequestPipeline"
```

---

### Task 7: ReActAgent 瘦身 + AgentConfig 调整

**Files:**
- Modify: `agent-core/src/main/java/cd/lan1akea/core/agent/ReActAgent.java`
- Modify: `agent-core/src/main/java/cd/lan1akea/core/agent/config/AgentConfig.java`

删除 Builder 内部类、doChat/doStream/loadSessionAndHistory/injectSystemMessage/resolveRuntimeContext/writeContext/resolveOptions/ensureBuilt。
改为薄门面，持有 LoopExecutor + RequestPipeline。

#### 瘦身后的 ReActAgent.java（完整）

```java
package cd.lan1akea.core.agent;

import cd.lan1akea.core.agent.config.AgentConfig;
import cd.lan1akea.core.agent.config.AgentExecutionConfig;
import cd.lan1akea.core.agent.loop.*;
import cd.lan1akea.core.CoreConstants.UI;
import cd.lan1akea.core.exception.AgentConfigurationException;
import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.hook.recorder.HookRecorder;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.metrics.AgentMetrics;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.state.AgentStateStore;
import cd.lan1akea.core.tool.*;
import cd.lan1akea.core.util.IdGenerator;
import cd.lan1akea.core.util.ValidationUtils;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * ReActAgent — 多租户并发安全的 Agent 实现。
 * 薄门面，请求处理全部委托给 RequestPipeline + LoopExecutor。
 */
public class ReActAgent implements StreamableAgent, CallableAgent {

    final String id;
    final String name;
    final AgentConfig config;
    final LoopExecutor loopExecutor;
    final RequestPipeline pipeline;
    final HookChain hookChain;
    final HookDispatcher hookDispatcher;
    final AroundHookChain aroundHookChain;

    AgentStateStore stateStore;
    ModelContextWindow contextWindow;
    HookRecorder hookRecorder;
    AgentMetrics metrics = AgentMetrics.NOOP;
    String systemMessage;
    private volatile boolean built;

    public ReActAgent(AgentConfig config) {
        ValidationUtils.notNull(config, "AgentConfig");
        ValidationUtils.notNull(config.getModel(), "ChatModel");

        this.config = config;
        this.id = IdGenerator.nextIdStr();
        this.name = config.getName();

        ChatModel model = config.getModel();
        ToolRegistry toolRegistry = config.getToolRegistry() != null
                ? config.getToolRegistry() : new ToolRegistry();
        ToolExecutor toolExecutor = new ToolExecutor(toolRegistry);

        this.hookChain = config.getHookChain() != null
                ? config.getHookChain() : new HookChain();
        this.hookDispatcher = new HookDispatcher(this.hookChain);
        this.aroundHookChain = config.getAroundHookChain() != null
                ? config.getAroundHookChain() : new AroundHookChain();

        this.stateStore = config.getStateStore();
        int maxInput = model.getMaxInputTokens();
        this.contextWindow = new ModelContextWindow(model.getModelName(), maxInput, maxInput / 2);

        // 组装内部组件
        LoopDecisionEngine engine = new LoopDecisionEngine();
        ToolCallOrchestrator toolOrch = new ToolCallOrchestrator(
                toolExecutor, toolRegistry, hookDispatcher, aroundHookChain);
        ModelCallPipeline modelPipeline = new ModelCallPipeline(
                model, hookDispatcher, toolRegistry, aroundHookChain, metrics);
        this.loopExecutor = new LoopExecutor(
                engine, modelPipeline, toolOrch, hookDispatcher, metrics);
        this.pipeline = new RequestPipeline(
                loopExecutor, stateStore, aroundHookChain,
                config.getExecutionConfig(), name, systemMessage);
    }

    // ============================================================
    // 公开 API
    // ============================================================

    @Override
    public Mono<ChatResponse> chat(List<Msg> messages) {
        ensureBuilt();
        return pipeline.execute(messages, null);
    }

    @Override
    public Mono<ChatResponse> chat(List<Msg> messages, cd.lan1akea.core.context.RuntimeContext ctx) {
        ensureBuilt();
        return pipeline.execute(messages, ctx);
    }

    @Override
    public Flux<ChatStreamChunk> stream(List<Msg> messages) {
        ensureBuilt();
        return pipeline.executeStream(messages, null);
    }

    @Override
    public Flux<ChatStreamChunk> stream(List<Msg> messages,
                                         cd.lan1akea.core.context.RuntimeContext ctx) {
        ensureBuilt();
        return pipeline.executeStream(messages, ctx);
    }

    @Override
    public Mono<ChatResponse> chat(List<Msg> messages, Class<?> outputClass) {
        return chat(StructuredOutputReminder.injectSchemaInstruction(messages, outputClass));
    }

    @Override
    public Flux<ChatStreamChunk> stream(List<Msg> messages, Class<?> outputClass) {
        return stream(StructuredOutputReminder.injectSchemaInstruction(messages, outputClass));
    }

    @Override
    public void interrupt() {
        pipeline.interrupt();
    }

    @Override
    public void interrupt(Msg feedbackMsg) {
        for (LoopContext ctx : pipeline.getActiveRequests().values()) {
            ctx.interrupt(feedbackMsg);
        }
    }

    public void interruptBySession(String sessionId) {
        pipeline.interruptBySession(sessionId);
    }

    // ============================================================
    // 生命周期
    // ============================================================

    public final Mono<Void> build() {
        if (built) {
            return Mono.error(new AgentConfigurationException(
                    UI.AGENT_PREFIX + name + UI.AGENT_ALREADY_BUILT));
        }
        built = true;
        return doBuild();
    }

    protected Mono<Void> doBuild() { return Mono.empty(); }

    public Mono<Void> shutdown() {
        built = false;
        pipeline.shutdown();
        return Mono.empty();
    }

    private void ensureBuilt() {
        if (!built) throw new AgentConfigurationException(
                UI.AGENT_PREFIX + name + UI.AGENT_NOT_BUILT);
    }

    // ============================================================
    // Getter / Setter
    // ============================================================

    @Override public String getName() { return name; }
    @Override public String getId() { return id; }
    public AgentConfig getConfig() { return config; }
    public ChatModel getModel() { return config.getModel(); }
    public ToolRegistry getToolRegistry() { return config.getToolRegistry(); }
    public HookChain getHookChain() { return hookChain; }
    public AgentStateStore getStateStore() { return stateStore; }
    public ModelContextWindow getContextWindow() { return contextWindow; }
    public HookRecorder getHookRecorder() { return hookRecorder; }
    public AgentMetrics getMetrics() { return metrics; }
    public boolean isBuilt() { return built; }
    public boolean isRunning() { return pipeline.isRunning(); }

    public void setStateStore(AgentStateStore v) { this.stateStore = v; }
    public void setSystemMessage(String msg) { this.systemMessage = msg; }
    public void setHookRecorder(HookRecorder v) {
        this.hookRecorder = v;
        if (this.hookDispatcher != null) this.hookDispatcher.setRecorder(v);
    }
    public void setMetrics(AgentMetrics v) { this.metrics = v != null ? v : AgentMetrics.NOOP; }
}
```

**AgentConfig 调整**: 确保 `getExecutionConfig()` 可用（检查是否有 getter）。

```bash
grep -n "getExecutionConfig\|executionConfig" agent-core/src/main/java/cd/lan1akea/core/agent/config/AgentConfig.java
```

如果缺少 getter，添加:

```java
public AgentExecutionConfig getExecutionConfig() { return executionConfig; }
```

- [ ] **Step 1: 重写 ReActAgent.java**

- [ ] **Step 2: 确保 AgentConfig.getExecutionConfig() 存在**

- [ ] **Step 3: 编译验证**

```bash
cd agent-core && mvn compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add agent-core/src/main/java/cd/lan1akea/core/agent/ReActAgent.java \
        agent-core/src/main/java/cd/lan1akea/core/agent/config/AgentConfig.java
git commit -m "refactor: slim ReActAgent to facade, delete Builder"
```

---

### Task 8: 清理旧代码 + 更新调用方 + 运行测试

**Files:**
- Delete: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/ReActLoop.java`
- Modify: 所有引用 ReActLoop / ReActAgent.Builder 的文件

- [ ] **Step 1: 查找所有引用 ReActLoop 的文件**

```bash
grep -rn "ReActLoop\|import.*loop.ReActLoop" agent-core/src/main/java/ agent-harness/src/main/java/ agent-bootstrap/src/main/java/
```

- [ ] **Step 2: 删除 ReActLoop.java**

```bash
rm agent-core/src/main/java/cd/lan1akea/core/agent/loop/ReActLoop.java
```

- [ ] **Step 3: 查找所有引用 ReActAgent.builder() 的文件**

```bash
grep -rn "ReActAgent.builder()\|new ReActAgent(" --include="*.java" .
```

- [ ] **Step 4: 更新调用方**

每个 `ReActAgent.builder()...build()` 替换为 `new ReActAgent(config)`。

AgentTool.java (agent-core):
```java
// 原来: ReActAgent subAgent = ReActAgent.builder().name(...).model(...).build();
// 改为: AgentConfig subConfig = AgentConfig.builder().name(...).model(...).build();
//       ReActAgent subAgent = new ReActAgent(subConfig);
```

HarnessAgent.java (agent-harness):
```java
// 原来: this.delegate = new ReActAgent(config);
// 不变，保留
```

- [ ] **Step 5: 更新 RequestPipeline（添加 getActiveRequests + shutdown）**

RequestPipeline 需要暴露 activeRequests 给 ReActAgent.interrupt(Msg)。

在 RequestPipeline.java 中添加:

```java
public ConcurrentHashMap<String, LoopContext> getActiveRequests() {
    return activeRequests;
}

public void shutdown() {
    activeRequests.clear();
}
```

- [ ] **Step 6: 编译 + 运行所有测试**

```bash
cd agent-core && mvn test -q
```

- [ ] **Step 7: 修复失败的测试**

预期失败的测试:
- `ReActAgentTest` — 如果使用 Builder，改为 `new ReActAgent(config)`
- `ReActLoopTest` 系列 — ReActLoop 已删除，这些测试需要重写或删除

如果测试用到了 ReActLoop 的内部方法（如 `reasoning()`, `executeSingleTool()`），需要迁移到对应新组件的测试。

- [ ] **Step 8: 最终编译验证全工程**

```bash
mvn compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "refactor: remove ReActLoop, update callers to new architecture"
```

---

## 实施检查清单

| # | Task | 状态 |
|---|------|------|
| 1 | Phase + Decision 类型 | [ ] |
| 2 | LoopDecisionEngine | [ ] |
| 3 | ToolCallOrchestrator | [ ] |
| 4 | ModelCallPipeline | [ ] |
| 5 | LoopExecutor | [ ] |
| 6 | LoopContextFactory + RequestPipeline | [ ] |
| 7 | ReActAgent 瘦身 | [ ] |
| 8 | 清理旧代码 + 测试 | [ ] |
