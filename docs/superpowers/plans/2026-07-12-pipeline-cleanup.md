# 主链路流水线简化实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 消除主链路中的值注入、静态方法错位、原始 dispatch 散落。新增 ChatResponseUtil、LoopContextAssembler、TokenEstimationHook，收拢 HookPipeline 模板方法，让 prepareAndExecute/executeReason/summarizeThenReason 极简化。

**Architecture:** 4 个新增类（ChatResponseUtil 工具类、LoopContextAssembler 装配器、TokenEstimationHook 系统 hook、HookPipeline 新增 3 个模板方法 + 2 个结果类型），2 个删除（LoopContextFactory、ModelCallPipeline.assembleResponseFromChunks），LoopExecutor 移除 TokenEstimator 直接依赖。

**Tech Stack:** Java 17+, Reactor (Mono/Flux), JUnit 5, Mockito

---

## File Structure

| 文件 | 操作 | 职责 |
|------|------|------|
| `core/util/ChatResponseUtil.java` | **Create** | 纯函数 chunks→ChatResponse |
| `core/agent/loop/LoopContextAssembler.java` | **Create** | LoopContext 完整装配 |
| `core/hook/impl/TokenEstimationHook.java` | **Create** | POST_MODEL 默认行为：写入ctx+token估算 |
| `core/hook/HookEventType.java` | Modify | 新增 POST_MODEL |
| `core/CoreConstants.java` | Modify | 新增 USAGE_CHUNK |
| `core/hook/HookPipeline.java` | Modify | 新增 onPostModel/preSummarize/onInterrupt |
| `core/agent/loop/ModelCallPipeline.java` | Modify | 移除 assembleResponseFromChunks |
| `core/agent/loop/LoopExecutor.java` | Modify | 简化方法，移除 TokenEstimator |
| `core/agent/loop/RequestPipeline.java` | Modify | EnrichedMessages→SessionLoadResult，prepareAndExecute 拍平 |
| `core/agent/loop/LoopContextFactory.java` | **Delete** | 逻辑迁入 LoopContextAssembler |
| `core/agent/ReActAgent.java` | Modify | 更新 LoopExecutor 构造，注册 TokenEstimationHook |
| `core/agent/loop/ModelCallPipelineTest.java` | Modify | 引用改为 ChatResponseUtil |
| `core/agent/loop/LoopExecutorTest.java` | Modify | 移除 TokenEstimator，更新 mock |

---

### Task 1: Create ChatResponseUtil

**Files:**
- Create: `agent-core/src/main/java/cd/lan1akea/core/util/ChatResponseUtil.java`

- [ ] **Step 1: Write ChatResponseUtil class**

Move `assembleResponseFromChunks` logic from `ModelCallPipeline` to this new util class.

```java
package cd.lan1akea.core.util;

import cd.lan1akea.core.CoreConstants.FinishReason;
import cd.lan1akea.core.message.AssistantMessage;
import cd.lan1akea.core.message.ContentBlock;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.TextBlock;
import cd.lan1akea.core.message.ToolUseBlock;
import cd.lan1akea.core.model.ChatResponse;
import cd.lan1akea.core.model.ChatStreamChunk;
import cd.lan1akea.core.model.ChatUsage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ChatResponse 工具方法。
 *
 * <p>提供从流式分块列表组装 ChatResponse 的纯函数。
 */
public final class ChatResponseUtil {

    private ChatResponseUtil() {}

    /**
     * 从流式分块列表组装单个 ChatResponse。
     *
     * <p>聚合文本增量（text）和工具调用分块（tool_use_start/delta）为完整响应。
     * 工具参数 JSON 经过 repairJson 修复常见 LLM 格式错误。
     *
     * @param chunks 流式分块列表
     * @return 组装后的聊天响应，chunks 为空时返回 null
     */
    public static ChatResponse fromChunks(List<ChatStreamChunk> chunks) {
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

        String finishReason = null;
        for (int i = chunks.size() - 1; i >= 0; i--) {
            if (chunks.get(i).getFinishReason() != null) {
                finishReason = chunks.get(i).getFinishReason();
                break;
            }
        }
        if (finishReason == null) finishReason = FinishReason.COMPLETED;

        List<ContentBlock> blocks = new ArrayList<>();
        if (!text.isEmpty()) {
            blocks.add(new TextBlock(text.toString()));
        }
        for (Map.Entry<String, String> e : toolArgs.entrySet()) {
            String id = e.getKey();
            blocks.add(new ToolUseBlock(id, toolNames.getOrDefault(id, ""),
                    JsonUtils.repairJson(e.getValue())));
        }

        Msg msg = new AssistantMessage(blocks, null);
        return new ChatResponse(msg, new ChatUsage(0, 0), finishReason, null);
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd agent-core && mvn compile -q`

- [ ] **Step 3: Commit**

```bash
git add agent-core/src/main/java/cd/lan1akea/core/util/ChatResponseUtil.java
git commit -m "feat: add ChatResponseUtil.fromChunks, extracted from ModelCallPipeline"
```

---

### Task 2: Add POST_MODEL to HookEventType and USAGE_CHUNK to CoreConstants

**Files:**
- Modify: `agent-core/src/main/java/cd/lan1akea/core/hook/HookEventType.java`
- Modify: `agent-core/src/main/java/cd/lan1akea/core/CoreConstants.java`

- [ ] **Step 1: Add POST_MODEL to HookEventType**

```java
// HookEventType.java — add after POST_TOOL_CALL:

/** 模型响应组装完成后（含完整 ChatResponse）。
 * 内置 TokenEstimationHook 处理写入 ctx + token 估算。 */
POST_MODEL,
```

- [ ] **Step 2: Add USAGE_CHUNK to CoreConstants.EventPayload**

```java
// CoreConstants.EventPayload — add after existing entries:

/** usage chunk（TokenEstimationHook → onPostModel 返回） */
public static final String USAGE_CHUNK = "usageChunk";
```

- [ ] **Step 3: Verify compilation**

Run: `cd agent-core && mvn compile -q`

- [ ] **Step 4: Commit**

```bash
git add agent-core/src/main/java/cd/lan1akea/core/hook/HookEventType.java agent-core/src/main/java/cd/lan1akea/core/CoreConstants.java
git commit -m "feat: add POST_MODEL hook type and USAGE_CHUNK constant"
```

---

### Task 3: Add onPostModel, preSummarize, onInterrupt to HookPipeline

**Files:**
- Modify: `agent-core/src/main/java/cd/lan1akea/core/hook/HookPipeline.java`

- [ ] **Step 1: Add PreSummarizeResult and InterruptResult inner classes**

```java
// Add to HookPipeline.java after existing members:

/**
 * PRE_SUMMARIZE Hook 处理结果。
 */
public static class PreSummarizeResult {

    /**
     * 结果动作类型。
     */
    public enum Action {
        /**
         * 继续循环（默认：已注入总结提示词 + 禁用工具）
         */
        CONTINUE,
        /**
         * 返回 chunk 给调用方下发（bypass / abort 后）
         */
        RETURN_CHUNK
    }

    private final Action action;
    private final ChatStreamChunk chunk;

    private PreSummarizeResult(Action action, ChatStreamChunk chunk) {
        this.action = action;
        this.chunk = chunk;
    }

    /**
     * 返回 chunk 给调用方（bypass 分支）。
     */
    public static PreSummarizeResult chunk(ChatStreamChunk c) {
        return new PreSummarizeResult(Action.RETURN_CHUNK, c);
    }

    /**
     * 继续循环（默认分支）。
     */
    public static PreSummarizeResult continueLoop() {
        return new PreSummarizeResult(Action.CONTINUE, null);
    }

    /**
     * @return 结果动作类型
     */
    public Action getAction() { return action; }

    /**
     * @return chunk（RETURN_CHUNK 时有效）
     */
    public ChatStreamChunk getChunk() { return chunk; }
}

/**
 * ON_INTERRUPT Hook 处理结果。
 */
public static class InterruptResult {

    /**
     * 结果动作类型。
     */
    public enum Action {
        /**
         * 恢复循环（有 feedback 消息已注入）
         */
        RECOVER,
        /**
         * 返回中断终止 chunk
         */
        RETURN_CHUNK
    }

    private final Action action;
    private final ChatStreamChunk chunk;

    private InterruptResult(Action action, ChatStreamChunk chunk) {
        this.action = action;
        this.chunk = chunk;
    }

    /**
     * 恢复循环。
     */
    public static InterruptResult recover() {
        return new InterruptResult(Action.RECOVER, null);
    }

    /**
     * 返回中断终止 chunk。
     */
    public static InterruptResult chunk(ChatStreamChunk c) {
        return new InterruptResult(Action.RETURN_CHUNK, c);
    }

    /**
     * @return 结果动作类型
     */
    public Action getAction() { return action; }

    /**
     * @return chunk（RETURN_CHUNK 时有效）
     */
    public ChatStreamChunk getChunk() { return chunk; }
}
```

- [ ] **Step 2: Add onPostModel method**

```java
// Add after aroundCall method:

// ============================================================
// 模型响应后管线
// ============================================================

/**
 * 模型响应后处理管线。
 *
 * <p>dispatch(POST_MODEL) → abort → error → 从 event 读取 usage chunk → 返回。
 * 默认行为由系统内置 TokenEstimationHook 提供（写入 ctx + token 估算 + 构建 usage chunk）。
 *
 * @param ctx      循环上下文
 * @param response 组装后的 ChatResponse
 * @return usage chunk 的 Mono
 */
public Mono<ChatStreamChunk> onPostModel(LoopContext ctx, ChatResponse response) {
    HookEvent event = new HookEvent(HookEventType.POST_MODEL);
    event.setPayload(EventPayload.LOOP_CONTEXT, ctx);
    event.setPayload(EventPayload.RESPONSE, response);
    HookContext hc = ctx.toHookContext();
    return dispatch(event, hc)
            .flatMap(r -> {
                if (r.isAbort())
                    return Mono.error(new HookAbortException(
                            HookSource.HOOK, r.getAbortReason()));
                return Mono.justOrEmpty(
                        (ChatStreamChunk) event.getPayload(EventPayload.USAGE_CHUNK));
            });
}
```

- [ ] **Step 3: Add preSummarize method**

```java
// Add after onPostModel:

// ============================================================
// 总结管线
// ============================================================

/**
 * 总结前管线。
 *
 * <p>dispatch(PRE_SUMMARIZE) → abort → error
 * → bypass → 注入 bypass 消息 + 返回 bypass chunk
 * → 默认 → 注入总结提示词 + 禁用工具，返回 continueLoop
 *
 * @param ctx 循环上下文
 * @return PreSummarizeResult
 */
public Mono<PreSummarizeResult> preSummarize(LoopContext ctx) {
    HookEvent event = new HookEvent(HookEventType.PRE_SUMMARIZE);
    event.setMessages(ctx.getMessages());
    HookContext hc = ctx.toHookContext();
    return dispatch(event, hc)
            .flatMap(r -> {
                if (r.isAbort())
                    return Mono.error(new HookAbortException(
                            HookSource.HOOK, r.getAbortReason()));
                if (event.getBypassMessage() != null) {
                    Msg bypass = event.getBypassMessage();
                    ctx.addMessage(bypass);
                    return Mono.just(PreSummarizeResult.chunk(
                            ChatStreamChunk.of(
                                    bypass.getTextContent(), FinishReason.STOP)));
                }
                ctx.addMessage(SystemMessage.of(
                        Prompt.MAX_ITERATIONS_SUMMARY + Prompt.MAX_ITERATIONS_NO_TOOLS));
                GenerateOptions opts = ctx.getGenerateOptions();
                ctx.setGenerateOptions(GenerateOptions.builder()
                        .temperature(opts.getTemperature())
                        .maxTokens(opts.getMaxTokens())
                        .toolChoice(ToolChoicePolicy.NONE)
                        .build());
                return Mono.just(PreSummarizeResult.continueLoop());
            });
}
```

- [ ] **Step 4: Add onInterrupt method**

```java
// Add after preSummarize:

// ============================================================
// 中断管线
// ============================================================

/**
 * 中断处理管线。
 *
 * <p>dispatch(ON_INTERRUPT) → abort → 返回中断终止 chunk
 * → 有 feedback → 注入 feedback + 清除中断 → 返回 recover
 * → 默认 → 返回中断终止 chunk
 *
 * @param ctx 循环上下文
 * @return InterruptResult
 */
public Mono<InterruptResult> onInterrupt(LoopContext ctx) {
    Msg feedback = ctx.getFeedbackMsg();
    HookEvent event = HookEvent.interrupt(
            feedback != null ? feedback.getTextContent() : UI.INTERRUPT_EXTERNAL, null);
    HookContext hc = ctx.toHookContext();
    return dispatch(event, hc)
            .flatMap(r -> {
                if (r.isAbort())
                    return Mono.just(InterruptResult.chunk(
                            ChatStreamChunk.of(
                                    UI.INTERRUPT_STREAM_PREFIX + r.getAbortReason()
                                            + UI.INTERRUPT_SUFFIX,
                                    FinishReason.INTERRUPTED)));
                if (feedback != null) {
                    ctx.addMessage(feedback);
                    ctx.clearInterrupt();
                    return Mono.just(InterruptResult.recover());
                }
                String reason = ctx.getLastResponse() != null
                        && ctx.getLastResponse().getMessage() != null
                        ? ctx.getLastResponse().getMessage().getTextContent()
                        : UI.INTERRUPT_EXEC;
                return Mono.just(InterruptResult.chunk(
                        ChatStreamChunk.of(
                                buildInterruptText(reason), FinishReason.INTERRUPTED)));
            });
}
```

- [ ] **Step 5: Add buildInterruptText private helper and required imports**

```java
// Add to private helpers section:
private String buildInterruptText(String reason) {
    return UI.INTERRUPT_PREFIX + reason + UI.INTERRUPT_SUFFIX;
}
```

Ensure imports include: `cd.lan1akea.core.CoreConstants.Prompt`, `cd.lan1akea.core.message.SystemMessage`, `cd.lan1akea.core.model.GenerateOptions`, `cd.lan1akea.core.model.ToolChoicePolicy`.

- [ ] **Step 6: Verify compilation**

Run: `cd agent-core && mvn compile -q`

- [ ] **Step 7: Commit**

```bash
git add agent-core/src/main/java/cd/lan1akea/core/hook/HookPipeline.java
git commit -m "feat: add onPostModel, preSummarize, onInterrupt to HookPipeline"
```

---

### Task 4: Create TokenEstimationHook

**Files:**
- Create: `agent-core/src/main/java/cd/lan1akea/core/hook/impl/TokenEstimationHook.java`

- [ ] **Step 1: Write TokenEstimationHook**

```java
package cd.lan1akea.core.hook.impl;

import cd.lan1akea.core.CoreConstants.EventPayload;
import cd.lan1akea.core.CoreConstants.Usage;
import cd.lan1akea.core.agent.loop.LoopContext;
import cd.lan1akea.core.hook.Hook;
import cd.lan1akea.core.hook.HookContext;
import cd.lan1akea.core.hook.HookEvent;
import cd.lan1akea.core.hook.HookEventType;
import cd.lan1akea.core.hook.HookResult;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.model.ChatResponse;
import cd.lan1akea.core.model.ChatStreamChunk;
import cd.lan1akea.core.model.TokenEstimator;
import cd.lan1akea.core.util.JsonUtils;

import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型响应后处理 Hook。
 *
 * <p>挂载在 POST_MODEL 上，执行默认响应处理：
 * <ol>
 *   <li>写入 ctx（lastResponse、tokens、assistant 消息）</li>
 *   <li>token 估算</li>
 *   <li>构建 usage chunk 供前端展示</li>
 * </ol>
 *
 * <p>其它 Hook 可同挂 POST_MODEL 拦截/扩展此行为。
 */
public class TokenEstimationHook implements Hook {

    private final TokenEstimator tokenEstimator;

    /**
     * 构建 TokenEstimationHook。
     *
     * @param tokenEstimator Token 估算器
     */
    public TokenEstimationHook(TokenEstimator tokenEstimator) {
        this.tokenEstimator = tokenEstimator;
    }

    @Override
    public HookEventType[] supports() {
        return new HookEventType[]{HookEventType.POST_MODEL};
    }

    @Override
    public Mono<HookResult> onEvent(HookEvent event, HookContext ctx) {
        LoopContext loopCtx = event.getPayload(EventPayload.LOOP_CONTEXT);
        ChatResponse resp = event.getPayload(EventPayload.RESPONSE);
        if (loopCtx == null || resp == null) return Mono.just(HookResult.continue_());

        loopCtx.setLastResponse(resp);
        if (resp.getUsage() != null) loopCtx.addTokens(resp.getUsage().getTotalTokens());
        Msg msg = resp.getMessage();
        if (msg != null) loopCtx.addMessage(msg);

        int promptTokens = tokenEstimator.estimate(loopCtx.getMessages());
        int completionTokens = msg != null ? tokenEstimator.estimate(msg) : 0;
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put(Usage.PROMPT_TOKENS, promptTokens);
        usage.put(Usage.COMPLETION_TOKENS, completionTokens);
        ChatStreamChunk usageChunk = ChatStreamChunk.builder()
                .delta(JsonUtils.toCompactJson(usage))
                .type(Usage.CHUNK_TYPE)
                .build();
        event.setPayload(EventPayload.USAGE_CHUNK, usageChunk);
        return Mono.just(HookResult.continue_());
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd agent-core && mvn compile -q`

- [ ] **Step 3: Commit**

```bash
git add agent-core/src/main/java/cd/lan1akea/core/hook/impl/TokenEstimationHook.java
git commit -m "feat: add TokenEstimationHook for POST_MODEL processing"
```

---

### Task 5: Rename EnrichedMessages to SessionLoadResult

**Files:**
- Modify: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/RequestPipeline.java`

- [ ] **Step 1: Rename class and add withMessages method**

Rename `EnrichedMessages` to `SessionLoadResult`. Replace the third constructor (merge pattern) with `withMessages`.

```java
// Replace the entire EnrichedMessages class with:
// Package-private so LoopContextAssembler (same package) can reference it
static class SessionLoadResult {
    final List<Msg> messages;
    final String interventionId;
    final String interventionType;
    final String pausedToolArgs;

    SessionLoadResult(List<Msg> messages) {
        this(messages, null, null, null);
    }

    SessionLoadResult(List<Msg> messages, String interventionId,
                     String interventionType, String pausedToolArgs) {
        this.messages = messages;
        this.interventionId = interventionId;
        this.interventionType = interventionType;
        this.pausedToolArgs = pausedToolArgs;
    }

    /**
     * 替换消息列表，保留介入信息。
     *
     * @param newMessages 新消息列表
     * @return 新的 SessionLoadResult
     */
    SessionLoadResult withMessages(List<Msg> newMessages) {
        return new SessionLoadResult(newMessages, interventionId, interventionType, pausedToolArgs);
    }
}
```

- [ ] **Step 2: Update all references**

Replace `EnrichedMessages` with `SessionLoadResult` throughout RequestPipeline.java:

```java
// loadSessionAndHistory return type:
private Mono<SessionLoadResult> loadSessionAndHistory(...)

// restoreFromCheckpoint return type:
private Mono<SessionLoadResult> restoreFromCheckpoint(...)

// All new EnrichedMessages(...) → new SessionLoadResult(...)

// loadHistory mapping:
.switchIfEmpty(loadHistory(sessionId, messages).map(SessionLoadResult::new))

// loadSessionAndHistory:
return Mono.just(new SessionLoadResult(messages));
// ...
.then(Mono.just(new SessionLoadResult(messages))));

// restoreFromCheckpoint:
return Mono.just(new SessionLoadResult(restored, ...));
// ...
return loadHistory(sessionId, messages).map(SessionLoadResult::new);
```

- [ ] **Step 3: Update prepareAndExecute lambda**

```java
// Change:
.flatMap(result -> injectSystemMessage(result.messages)
        .map(enrichedMsgs -> new EnrichedMessages(enrichedMsgs, result)))
// To:
.flatMap(result -> injectSystemMessage(result.messages)
        .map(result::withMessages))
```

- [ ] **Step 4: Verify compilation**

Run: `cd agent-core && mvn compile -q`

- [ ] **Step 5: Commit**

```bash
git add agent-core/src/main/java/cd/lan1akea/core/agent/loop/RequestPipeline.java
git commit -m "refactor: rename EnrichedMessages to SessionLoadResult, add withMessages"
```

---

### Task 6: Create LoopContextAssembler

**Files:**
- Create: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/LoopContextAssembler.java`

- [ ] **Step 1: Write LoopContextAssembler**

```java
package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.agent.config.AgentExecutionConfig;
import cd.lan1akea.core.context.RuntimeContext;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.model.GenerateOptions;

import java.util.List;

/**
 * LoopContext 装配器。
 *
 * <p>收拢 LoopContext 的完整构建逻辑，包括 GenerateOptions 映射和介入状态注入。
 * 调用方只需一行 {@code LoopContextAssembler.assemble(ctx, execConfig, sessionResult)}。
 */
public final class LoopContextAssembler {

    private LoopContextAssembler() {}

    /**
     * 从 RuntimeContext、AgentExecutionConfig 和会话加载结果构建 LoopContext。
     *
     * @param ctx           运行时上下文
     * @param execConfig    执行配置
     * @param sessionResult 会话加载结果（含消息和介入信息）
     * @return 构建完成的 LoopContext
     */
    public static LoopContext assemble(RuntimeContext ctx, AgentExecutionConfig execConfig,
                                        RequestPipeline.SessionLoadResult sessionResult) {
        LoopContext lc = LoopContext.builder()
                .agentName(ctx.getAgentName())
                .fromRuntimeContext(ctx)
                .messages(sessionResult.messages)
                .generateOptions(mapGenerateOptions(execConfig))
                .maxIterations(execConfig.getMaxIterations())
                .backoffMs(execConfig.getIterationBackoffMs())
                .build();
        applyIntervention(lc, sessionResult);
        return lc;
    }

    /**
     * 从执行配置映射生成选项。
     */
    private static GenerateOptions mapGenerateOptions(AgentExecutionConfig c) {
        return GenerateOptions.builder()
                .temperature(c.getTemperature())
                .maxTokens(c.getMaxTokens())
                .toolChoice(c.getToolChoice())
                .build();
    }

    /**
     * 将会话加载结果中的介入信息写入 LoopContext。
     */
    private static void applyIntervention(LoopContext lc, RequestPipeline.SessionLoadResult result) {
        if (result.interventionId != null) {
            lc.getInterventionState().setInterventionId(result.interventionId);
            lc.getInterventionState().setInterventionType(result.interventionType);
            lc.getInterventionState().setPausedToolArgs(result.pausedToolArgs);
        }
    }
}
```

Note: `SessionLoadResult` is a package-private class inside `RequestPipeline`. Since `LoopContextAssembler` is in the same package, it can reference `RequestPipeline.SessionLoadResult` directly. If compiler doesn't allow this, we'll make `SessionLoadResult` package-private static class.

- [ ] **Step 2: Verify compilation**

Run: `cd agent-core && mvn compile -q`

- [ ] **Step 3: Commit**

```bash
git add agent-core/src/main/java/cd/lan1akea/core/agent/loop/LoopContextAssembler.java
git commit -m "feat: add LoopContextAssembler, absorb LoopContextFactory logic"
```

---

### Task 7: Delete LoopContextFactory

**Files:**
- Delete: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/LoopContextFactory.java`

- [ ] **Step 1: Delete the file**

```bash
rm agent-core/src/main/java/cd/lan1akea/core/agent/loop/LoopContextFactory.java
```

- [ ] **Step 2: Verify compilation**

Run: `cd agent-core && mvn compile -q`

- [ ] **Step 3: Commit**

```bash
git add agent-core/src/main/java/cd/lan1akea/core/agent/loop/LoopContextFactory.java
git commit -m "refactor: remove LoopContextFactory, superseded by LoopContextAssembler"
```

---

### Task 8: Update ModelCallPipeline — remove assembleResponseFromChunks

**Files:**
- Modify: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/ModelCallPipeline.java`

- [ ] **Step 1: Remove assembleResponseFromChunks static method**

Delete the entire `assembleResponseFromChunks` method (lines 99-143) and its Javadoc.

- [ ] **Step 2: Update execute method to use ChatResponseUtil**

```java
// Change:
public Mono<ChatResponse> execute(LoopContext ctx) {
    return executeStream(ctx).collectList().map(ModelCallPipeline::assembleResponseFromChunks);
}

// To:
public Mono<ChatResponse> execute(LoopContext ctx) {
    return executeStream(ctx).collectList().map(ChatResponseUtil::fromChunks);
}
```

Add import: `import cd.lan1akea.core.util.ChatResponseUtil;`

Remove unused imports that were only used by `assembleResponseFromChunks`: `AssistantMessage`, `ContentBlock`, `TextBlock`, `ToolUseBlock`, `ChatResponse`, `ChatUsage`, `ArrayList`, `LinkedHashMap`, `Map` (check each one — some may still be needed elsewhere in the file).

- [ ] **Step 3: Verify compilation**

Run: `cd agent-core && mvn compile -q`

- [ ] **Step 4: Commit**

```bash
git add agent-core/src/main/java/cd/lan1akea/core/agent/loop/ModelCallPipeline.java
git commit -m "refactor: remove assembleResponseFromChunks, use ChatResponseUtil::fromChunks"
```

---

### Task 9: Simplify LoopExecutor

**Files:**
- Modify: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/LoopExecutor.java`

- [ ] **Step 1: Remove TokenEstimator field and constructor parameter**

Remove:
- Field: `private final TokenEstimator tokenEstimator;`
- Constructor parameter: `TokenEstimator tokenEstimator,`
- Constructor assignment: `this.tokenEstimator = tokenEstimator;`
- Import: `import cd.lan1akea.core.model.TokenEstimator;`

- [ ] **Step 2: Simplify executeReason**

Replace the entire `executeReason` method:

```java
/**
 * 执行推理阶段：调用模型获取回复。
 *
 * <p>流式收集模型分块 → ChatResponseUtil.fromChunks 组装 → HookPipeline.onPostModel 后处理。
 * 后处理（写入 ctx + token 估算 + usage chunk）由 TokenEstimationHook 默认执行。
 *
 * @param ctx 循环上下文
 * @return 模型推理的流式分块
 */
private Flux<ChatStreamChunk> executeReason(LoopContext ctx) {
    List<ChatStreamChunk> buffer = new ArrayList<>();
    return modelPipeline.executeStream(ctx)
            .doOnNext(buffer::add)
            .concatWith(Flux.defer(() -> {
                ChatResponse resp = ChatResponseUtil.fromChunks(buffer);
                return resp != null
                        ? hookPipeline.onPostModel(ctx, resp)
                        : Flux.empty();
            }));
}
```

Remove imports no longer needed: `LinkedHashMap` (check if used elsewhere). Add import: `import cd.lan1akea.core.util.ChatResponseUtil;`

- [ ] **Step 3: Simplify summarizeThenReason**

Replace the entire `summarizeThenReason` method:

```java
/**
 * 达到最大迭代时注入总结提示词并进入最后一轮推理。
 *
 * <p>委托 HookPipeline.preSummarize 处理 PRE_SUMMARIZE 分发和注入逻辑。
 *
 * @param ctx 循环上下文
 * @return 总结轮次的流式 chunk 序列
 */
private Flux<ChatStreamChunk> summarizeThenReason(LoopContext ctx) {
    return hookPipeline.preSummarize(ctx)
            .flatMapMany(r -> {
                if (r.getAction() == HookPipeline.PreSummarizeResult.Action.RETURN_CHUNK)
                    return Flux.just(r.getChunk());
                return reasonThenActOrObserve(ctx);
            });
}
```

Remove imports now unused: `HookAbortException`, `HookSource`, `ToolChoicePolicy`, `SystemMessage`, `Prompt` (check each — some may still be used in handleInterruptStream or other methods).

- [ ] **Step 4: Simplify handleInterruptStream**

Replace the entire `handleInterruptStream` method:

```java
/**
 * 处理中断流：分发中断事件 Hook，根据结果决定中止或恢复。
 *
 * <p>委托 HookPipeline.onInterrupt 处理 ON_INTERRUPT 分发和分支逻辑。
 *
 * @param ctx 循环上下文
 * @return 中断处理后的流式 chunk 序列
 */
private Flux<ChatStreamChunk> handleInterruptStream(LoopContext ctx) {
    return hookPipeline.onInterrupt(ctx)
            .flatMapMany(r -> {
                if (r.getAction() == HookPipeline.InterruptResult.Action.RECOVER)
                    return runStream(ctx);
                return Flux.just(r.getChunk());
            });
}
```

- [ ] **Step 5: Remove buildInterruptedResponse static method**

Delete the `buildInterruptedResponse` method — `buildInterruptText` in HookPipeline now handles this.

- [ ] **Step 6: Verify compilation**

Run: `cd agent-core && mvn compile -q`

- [ ] **Step 7: Commit**

```bash
git add agent-core/src/main/java/cd/lan1akea/core/agent/loop/LoopExecutor.java
git commit -m "refactor: simplify LoopExecutor — extract hook logic to HookPipeline, remove TokenEstimator"
```

---

### Task 10: Flatten RequestPipeline prepareAndExecute

**Files:**
- Modify: `agent-core/src/main/java/cd/lan1akea/core/agent/loop/RequestPipeline.java`

- [ ] **Step 1: Replace prepareAndExecute**

```java
/**
 * 加载会话、注入系统消息、构建 LoopContext 并执行。
 *
 * @param ctx      运行时上下文
 * @param messages 当前请求消息
 * @return 流式响应分块
 */
private Flux<ChatStreamChunk> prepareAndExecute(RuntimeContext ctx, List<Msg> messages) {
    return loadSessionAndHistory(ctx, messages)
            .flatMap(result -> injectSystemMessage(result.messages)
                    .map(result::withMessages))
            .map(result -> LoopContextAssembler.assemble(ctx, execConfig, result))
            .flatMapMany(this::executeWithTracking);
}

/**
 * 注册活跃请求，通过 SessionGate 排队后执行流式循环。
 *
 * <p>使用 Flux.using 保证异常/取消路径下正确清理活跃请求记录。
 *
 * @param loopCtx 循环上下文
 * @return 流式响应分块
 */
private Flux<ChatStreamChunk> executeWithTracking(LoopContext loopCtx) {
    return Flux.using(
            () -> trackActive(loopCtx),
            lc -> withTimeoutAndGate(lc, loopExecutor.runStream(lc)),
            this::untrackActive);
}
```

- [ ] **Step 2: Update execute method to use ChatResponseUtil**

```java
// Change:
public Mono<ChatResponse> execute(List<Msg> messages, RuntimeContext rtCtx) {
    return executeStream(messages, rtCtx)
            .collectList()
            .map(ModelCallPipeline::assembleResponseFromChunks);
}

// To:
public Mono<ChatResponse> execute(List<Msg> messages, RuntimeContext rtCtx) {
    return executeStream(messages, rtCtx)
            .collectList()
            .map(ChatResponseUtil::fromChunks);
}
```

Add import: `import cd.lan1akea.core.util.ChatResponseUtil;`

- [ ] **Step 3: Update Javadoc references**

Update class-level Javadoc and method Javadoc to reference `SessionLoadResult` instead of `EnrichedMessages`, and `LoopContextAssembler` instead of `LoopContextFactory`.

Remove unused import `LoopContextFactory`. Check if `GenerateOptions` is still used (shouldn't be after removing `resolveOptions`).

- [ ] **Step 4: Verify compilation**

Run: `cd agent-core && mvn compile -q`

- [ ] **Step 5: Commit**

```bash
git add agent-core/src/main/java/cd/lan1akea/core/agent/loop/RequestPipeline.java
git commit -m "refactor: flatten prepareAndExecute, use LoopContextAssembler and ChatResponseUtil"
```

---

### Task 11: Update ReActAgent — register TokenEstimationHook, remove TokenEstimator from LoopExecutor

**Files:**
- Modify: `agent-core/src/main/java/cd/lan1akea/core/agent/ReActAgent.java`

- [ ] **Step 1: Register TokenEstimationHook on HookChain**

Add after `hookChain.register(new AgentMetricsHook(...))`:

```java
hookChain.register(new TokenEstimationHook(contextWindow.getEstimator()));
```

Add import: `import cd.lan1akea.core.hook.impl.TokenEstimationHook;`

- [ ] **Step 2: Remove TokenEstimator from LoopExecutor constructor call**

```java
// Change:
LoopExecutor loopExecutor = new LoopExecutor(
        modelPipeline, toolOrch, hookPipeline,
        contextWindow.getEstimator(), interventionResolver);

// To:
LoopExecutor loopExecutor = new LoopExecutor(
        modelPipeline, toolOrch, hookPipeline, interventionResolver);
```

- [ ] **Step 3: Verify compilation**

Run: `cd agent-core && mvn compile -q`

- [ ] **Step 4: Commit**

```bash
git add agent-core/src/main/java/cd/lan1akea/core/agent/ReActAgent.java
git commit -m "refactor: register TokenEstimationHook, remove TokenEstimator from LoopExecutor"
```

---

### Task 12: Update tests

**Files:**
- Modify: `agent-core/src/test/java/cd/lan1akea/core/agent/loop/ModelCallPipelineTest.java`
- Modify: `agent-core/src/test/java/cd/lan1akea/core/agent/loop/LoopExecutorTest.java`

- [ ] **Step 1: Update ModelCallPipelineTest**

Change the test to use `ChatResponseUtil.fromChunks`:

```java
// Change:
ChatResponse resp = ModelCallPipeline.assembleResponseFromChunks(chunks);

// To:
ChatResponse resp = ChatResponseUtil.fromChunks(chunks);
```

Add import: `import cd.lan1akea.core.util.ChatResponseUtil;`

- [ ] **Step 2: Update LoopExecutorTest — remove TokenEstimator from constructor**

```java
// Change:
executor = new LoopExecutor(modelPipeline, orchestrator, hookPipeline,
        new Cl100kTokenEstimator(), resolver);

// To:
executor = new LoopExecutor(modelPipeline, orchestrator, hookPipeline, resolver);
```

Remove import: `import cd.lan1akea.core.model.Cl100kTokenEstimator;`

- [ ] **Step 3: Add TokenEstimationHook mock in setUp**

Since the HookPipeline now dispatches POST_MODEL and expects a TokenEstimationHook to respond, the existing test setup needs to handle the POST_MODEL dispatch. Update the `setUp` method to return a usage chunk when POST_MODEL is dispatched:

```java
@BeforeEach
void setUp() {
    MockitoAnnotations.openMocks(this);

    toolRegistry = new ToolRegistry();
    AroundHookChain aroundHooks = new AroundHookChain();
    hookDispatcher = spy(new HookDispatcher(new HookChain()));
    // Default: return continue for all hooks
    doReturn(Mono.just(HookResult.continue_())).when(hookDispatcher).dispatch(any(), any());
    // POST_MODEL: return usage chunk to avoid NPE
    doAnswer(inv -> {
        HookEvent event = inv.getArgument(0);
        if (event.getHookEventType() == HookEventType.POST_MODEL) {
            event.setPayload("usageChunk",
                    ChatStreamChunk.builder().delta("{}").type("usage").build());
        }
        return Mono.just(HookResult.continue_());
    }).when(hookDispatcher).dispatch(
            argThat(e -> e.getHookEventType() == HookEventType.POST_MODEL), any());

    HookPipeline hookPipeline = new HookPipeline(hookDispatcher, aroundHooks);
    ModelCallPipeline modelPipeline = new ModelCallPipeline(
            model, hookPipeline, toolRegistry);
    ToolCallOrchestrator orchestrator = new ToolCallOrchestrator(
            toolExecutor, toolRegistry, hookPipeline);

    cd.lan1akea.core.intervention.InMemoryInterventionStore store =
            new cd.lan1akea.core.intervention.InMemoryInterventionStore();
    InterventionResolver resolver = new InterventionResolver(store, orchestrator);
    executor = new LoopExecutor(modelPipeline, orchestrator, hookPipeline, resolver);
}
```

Add import: `import cd.lan1akea.core.model.ChatStreamChunk;` (may already be imported)

- [ ] **Step 4: Verify login information at assertion**

Existing tests check for usage chunk — update assertion if needed. The `runStream_textOnly_shouldEmitChunks` test checks `"usage".equals(c.getType())` which should still work.

- [ ] **Step 5: Run tests**

Run: `cd agent-core && mvn test -pl . -Dtest="LoopExecutorTest,ModelCallPipelineTest" -q`

- [ ] **Step 6: Commit**

```bash
git add agent-core/src/test/java/cd/lan1akea/core/agent/loop/ModelCallPipelineTest.java agent-core/src/test/java/cd/lan1akea/core/agent/loop/LoopExecutorTest.java
git commit -m "test: update tests for pipeline cleanup changes"
```

---

### Task 13: Run full test suite

**Files:**
- None (verification only)

- [ ] **Step 1: Run all tests**

Run: `cd agent-core && mvn test`

- [ ] **Step 2: Fix any failing tests**

Check for compilation errors or test failures. Common issues:
- Unused imports in modified files
- Missing import for `ChatResponseUtil` in any remaining callers
- `assembleResponseFromChunks` references in other test files

- [ ] **Step 3: Commit any fixes**

```bash
git add -A
git commit -m "fix: test compilation and failures after pipeline cleanup"
```
