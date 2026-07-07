# 主链路代码优化设计

## 目标

中等重构：提取 Intervention 状态机 + 修复边界问题 + 清理样板代码。保持四阶段状态机架构不变，保持所有外部 API 不变。

---

## 一、新增 InterventionResolver

### 动机

`LoopExecutor` 当前 670 行，其中干预相关逻辑约 150 行（`resumeFromIntervention`、`handleIntervention`、`extractToolCallIdByName`、`executeResumeTool`、`resolveAndContinue`、`interventionChunk`、`lastAssistantIndex`、`truncateMessages`、`toInterventionType`、`handleToolError` 中的分发逻辑）。这些方法涉及 3 个外部依赖（`InterventionStore`、`ToolCallOrchestrator`、`JsonUtils`），与 LoopExecutor 的核心职责（状态机路由、阶段执行）内聚性低。

当前 `resumeFromIntervention` 的 6 路 switch 和 `resolveAndContinue` 的 5 步流程揉在一起，缺乏可测试的中间层。

### 设计：决策-执行分离

关键洞察：`resumeFromIntervention` 的 6 条分支可归为 3 种 Action：

```
PENDING/default    → 返回介入等待 chunk，终止当前流
req==null, callId==null → 清除介入状态，重新进入主循环
APPROVED/CLARIFIED/DENIED/EXPIRED → 执行工具（或构建失败结果），插入 tool_result，继续 Observe 阶段
```

LoopExecutor 只需要知道"做什么"，不需要知道"怎么决定"。因此：

- **InterventionResolver** 负责决策 + 数据准备，返回 `ResolvedIntervention`（Action + 执行所需数据）
- **LoopExecutor** 保留 `resolveAndContinue`（因为要调用私有 `executePhase` 进入状态机），根据 Action 选择行为

### 新文件: `InterventionResolver.java`

路径：`agent-core/src/main/java/cd/lan1akea/core/agent/loop/InterventionResolver.java`

#### 内部类 ResolvedIntervention

```java
/**
 * 介入恢复决策结果。
 * <p>将 resumeFromIntervention 的 6 条分支归为 3 种 Action，
 * 避免 InterventionResolver 直接返回 Flux 而依赖 executePhase。
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

    public static ResolvedIntervention reEnter() {
        return new ResolvedIntervention(Action.RE_ENTER, null, null, null);
    }

    public static ResolvedIntervention returnChunk(ChatStreamChunk chunk) {
        return new ResolvedIntervention(Action.RETURN_CHUNK, chunk, null, null);
    }

    public static ResolvedIntervention executeAndContinue(String callId, Mono<ToolResult> execution) {
        return new ResolvedIntervention(Action.EXECUTE_AND_CONTINUE, null, callId, execution);
    }

    public Action getAction() { return action; }
    public ChatStreamChunk getChunk() { return chunk; }
    public String getCallId() { return callId; }
    public Mono<ToolResult> getExecution() { return execution; }
}
```

#### 公开方法

**1. resolveForRecovery**

```java
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
public ResolvedIntervention resolveForRecovery(LoopContext ctx)
```

**2. createIntervention**

```java
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
public ChatStreamChunk createIntervention(HumanInterventionException e, LoopContext ctx)
```

**3. findToolCallId**

```java
/**
 * 按工具名查找最后一条 assistant 消息中对应 tool_use 的 callId。
 * 无匹配时回退到最后一个 tool_use 的 callId。
 *
 * @param ctx      循环上下文
 * @param toolName 工具名称
 * @return callId，无 assistant 消息时返回 null
 */
public String findToolCallId(LoopContext ctx, String toolName)
```

**4. buildSignalChunk**

```java
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
                                         String interventionType, String toolName)
```

#### 私有方法

| 方法 | 说明 |
|------|------|
| `lastAssistantIndex(ctx)` | 找最后一条 assistant 消息索引，-1 表示不存在 |
| `truncateMessages(messages)` | 截断至最近 `Intervention.RECENT_MSG_LIMIT` 条 |
| `toInterventionType(t)` | `HumanInterventionException.Type` → `InterventionRequest.Type` |
| `resolveArgs(req, ctx)` | APPROVED 时：优先 `req.getToolArgs()`，回退 `ctx.getPausedToolArgs()`，再回退空 Map |
| `buildRequest(e, ctx)` | 从异常构建 `InterventionRequest` |
| `buildPendingChunk(req)` | PENDING 状态的等待 chunk |
| `buildDeniedMessage(req)` | DENIED 状态的失败消息 |
| `buildExpiredMessage(req)` | EXPIRED 状态的失败消息 |
| `executeResumeTool(ctx, toolName, args, callId)` | 构建 ToolCallContext（标记 approved）→ `toolOrchestrator.executeDirect` |

### LoopExecutor 变化

**删除的方法（8 个）：**

| 方法 | 行数 | 移至 |
|------|------|------|
| `resumeFromIntervention` | 45 | `InterventionResolver.resolveForRecovery` |
| `handleIntervention` | 23 | `InterventionResolver.createIntervention` |
| `extractToolCallIdByName` | 9 | `InterventionResolver.findToolCallId` |
| `executeResumeTool` | 12 | `InterventionResolver` (private) |
| `interventionChunk` | 14 | `InterventionResolver.buildSignalChunk` |
| `lastAssistantIndex` | 8 | `InterventionResolver` (private) |
| `truncateMessages` | 5 | `InterventionResolver` (private) |
| `toInterventionType` | 5 | `InterventionResolver` (private) |

**保留的方法：**

- `resolveAndContinue` — 需要调用私有的 `executePhase(Decision.continue_(Phase.observe()), ctx)` 进入状态机。这是 LoopExecutor 的核心路由能力，不可移出。

**修改的方法：**

`runStream` 中恢复入口（约 4 行 → dispatch 模式）：

```java
// Before
if (ctx.getInterventionId() != null && !ctx.isInterrupted()) {
    return resumeFromIntervention(ctx);
}

// After
if (ctx.getInterventionState().hasPending() && !ctx.isInterrupted()) {
    ResolvedIntervention resolved = interventionResolver.resolveForRecovery(ctx);
    switch (resolved.getAction()) {
        case RE_ENTER: return runStream(ctx);
        case RETURN_CHUNK: return Flux.just(resolved.getChunk());
        case EXECUTE_AND_CONTINUE:
            return resolveAndContinue(ctx, resolved.getCallId(), resolved.getExecution());
        default: return Flux.empty();
    }
}
```

`handleToolError` 中异常分发（1 行变化）：

```java
// Before
return handleIntervention(hie, ctx);

// After
return Flux.just(interventionResolver.createIntervention(hie, ctx));
```

`resolveAndContinue` 中状态清除（3 行 → 1 行）：

```java
// Before
ctx.setInterventionId(null);
ctx.setInterventionType(null);
ctx.setPausedToolArgs(null);

// After
ctx.getInterventionState().clear();
```

### 依赖注入

`InterventionResolver` 在 `ReActAgent` 构造函数中创建，与 `LoopExecutor` 同级注入：

```java
InterventionStore interventionStore = config.getInterventionStore() != null
        ? config.getInterventionStore() : new InMemoryInterventionStore();
InterventionResolver interventionResolver = new InterventionResolver(
        interventionStore, toolOrch);
this.loopExecutor = new LoopExecutor(
        engine, modelPipeline, toolOrch, hookDispatcher, metrics,
        interventionStore, contextWindow.getEstimator(), interventionResolver);
```

`LoopExecutor` 构造函数增加第 8 个参数 `InterventionResolver`，`interventionStore` 参数保留（`handleInterruptStream` 中不直接使用，但 `dispatchSummarizeHook` 等不涉及）。

实际上审视后发现 LoopExecutor 构造后不再直接使用 `interventionStore`——`resumeFromIntervention` 和 `handleIntervention` 都移除了。LoopExecutor 保留 `interventionStore` 依赖仅因 `truncateMessages` 引用的常量 `Intervention.RECENT_MSG_LIMIT`（静态常量，不需要实例）。可以移除 LoopExecutor 的 `interventionStore` 字段。

---

## 二、LoopContext 干预状态收拢

### 动机

LoopContext 当前 3 个干预字段散落在 15 个字段之间，通过 6 个 getter/setter 暴露。在 `LoopExecutor`、`RequestPipeline`、`SessionPersistenceHook`、`AgentState` 四个组件间以字段级粒度传递。每次新增干预字段（历史上从 1 个 `interventionId` 逐步增加到 3 个）需要改四处。

### 新增内部类

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

### LoopContext 变化

- 删除：`interventionId`、`interventionType`、`pausedToolArgs` 字段及其 6 个 getter/setter
- 新增：`private final InterventionState interventionState = new InterventionState();`
- 新增：`public InterventionState getInterventionState() { return interventionState; }`

### 引用更新（4 个文件）

| 文件 | 变更 |
|------|------|
| `LoopExecutor` | `ctx.getInterventionId()` → `ctx.getInterventionState().getInterventionId()`；三行置 null → `ctx.getInterventionState().clear()` |
| `RequestPipeline` | `loopCtx.setInterventionId(...)` → `loopCtx.getInterventionState().setInterventionId(...)` |
| `SessionPersistenceHook` | `ctx.getInterventionId()` → `ctx.getInterventionState().getInterventionId()` |
| `InterventionResolver`（新） | 直接使用 `ctx.getInterventionState()` |

外部使用者（Controller 层）不直接访问 LoopContext 的干预字段，无影响。

---

## 三、边界问题修复

### #4 SessionPersistenceHook 静默吞异常

**风险：** `persistTurn` 和 `saveCheckpoint` 使用裸 `.subscribe()`，若持久化失败（DB 断开、序列化异常），错误被 JVM 捕获但无日志、无告警。在长时间运行后可能丢失全部会话历史而不被察觉。

**文件：** `SessionPersistenceHook.java`

```java
// Before — 异常静默丢失
stateStore.addTurn(new SessionId(sessionId), turn).subscribe();
stateStore.saveCheckpoint(state).subscribe();

// After — 至少记录日志
stateStore.addTurn(new SessionId(sessionId), turn)
        .doOnError(e -> log.warning("持久化Turn失败[session=" + sessionId + "]: " + e.getMessage()))
        .onErrorComplete()
        .subscribe();
stateStore.saveCheckpoint(state)
        .doOnError(e -> log.warning("持久化Checkpoint失败[session=" + sessionId + "]: " + e.getMessage()))
        .onErrorComplete()
        .subscribe();
```

`onErrorComplete()` 将错误信号转为正常完成，避免 `.subscribe()` 因未处理错误而抛出 UnsupportedOperationException（Reactor 默认行为）。

### #5 activeRequests 泄漏

**风险：** `doFinally` 在特定 JVM 错误（如 OutOfMemoryError 在错误的调用栈层级）或 Reactor 内部调度异常时可能不被调用。`ConcurrentHashMap` 的 key 只增不减会阻止 GC，且 `interrupt()` 遍历的活跃请求中包含已完成的请求。

**文件：** `RequestPipeline.java`

改用 `Flux.using` / `Mono.using` —— 其 cleanup 在 cancel、error、complete 三条路径上都保证执行：

```java
// executeStream
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

// execute 同理，用 Mono.using
```

### #6 executeAct 指标时机

**原因：** `iteration` 在 `executeObserve` 中递增，但 `executeAct` 中记录指标时 iteration 尚未递增。当前用 `+1` 是正确的。

**文件：** `LoopExecutor.java` —— 不改逻辑，加注释说明：

```java
// iteration 在 executeObserve 中递增，此处 +1 记录即将进入的迭代号
metrics.recordIteration(ctx.getAgentName(), ctx.getSessionId(),
        ctx.getIteration() + 1, toolCalls.size());
```

### #7 assembleResponseFromChunks 默认 finishReason

**风险：** chunks 非空但无任何 chunk 带 finishReason（如模型断流）时，默认为 COMPLETED，掩盖了不完整响应。

**文件：** `ModelCallPipeline.java`

```java
// Before — 初始值掩盖了"无 finishReason"的情况
String finishReason = FinishReason.COMPLETED;

// After — 显式 null → 兜底
String finishReason = null;
// ... search loop unchanged ...
if (finishReason == null) finishReason = FinishReason.COMPLETED;
```

### #8 resolveAndContinue insertAt 边界

**风险：** `lastAssistantIndex` 返回 -1（消息列表无 assistant 消息）时，`insertAt = 0`，tool_result 被插入消息列表首位，产生非法消息序列。

**文件：** `LoopExecutor.java`

```java
// Before
int insertAt = lastAssistantIndex(ctx) + 1;

// After
int lastAssistant = lastAssistantIndex(ctx);
int insertAt = lastAssistant >= 0 ? lastAssistant + 1 : ctx.getMessages().size();
```

### #9 handleInterruptStream NPE

**风险：** `ctx.getLastResponse().getMessage()` 可能为 null（模型返回空响应），直接调用 `.getTextContent()` 会 NPE。当前外层已判空 `getLastResponse()`，但 `getMessage()` 未判空。

**文件：** `LoopExecutor.java`

```java
// Before
String reason = ctx.getLastResponse() != null
        ? ctx.getLastResponse().getMessage().getTextContent()
        : UI.INTERRUPT_EXEC;

// After
Msg lastMsg = ctx.getLastResponse() != null
        ? ctx.getLastResponse().getMessage() : null;
String reason = lastMsg != null ? lastMsg.getTextContent() : UI.INTERRUPT_EXEC;
```

### #11 Builder null 安全

**风险：** `Builder.messages(null)` → `new ArrayList<>(null)` → NPE。`Builder.generateOptions(null)` → `applySummarizeFallback` 和 `executeAct` 中间接 NPE。

**文件：** `LoopContext.java` —— 在构造函数中加兜底：

```java
this.messages = builder.messages != null
        ? new ArrayList<>(builder.messages) : new ArrayList<>();
this.generateOptions = builder.generateOptions != null
        ? builder.generateOptions : GenerateOptions.defaults();
```

---

## 四、样板代码清理

3 个类中存在逐字相同的私有方法，直接内联 `ctx.toHookContext()`：

| 文件 | 删除方法 | 调用处替换 |
|------|---------|-----------|
| `LoopExecutor` | `buildHookContext(ctx)` | `ctx.toHookContext()`（3 处：`dispatchSummarizeHook`、`dispatchAfterIteration`、`handleInterruptStream`） |
| `ModelCallPipeline` | `buildHookContext(ctx)` | `ctx.toHookContext()`（1 处：`executeStream`） |
| `ToolCallOrchestrator` | `buildHookContext(ctx)` | `ctx.toHookContext()`（2 处：`execute`、`executeDirect`） |

`RequestPipeline` 和 `SessionPersistenceHook` 无此方法，不涉及。

---

## 五、不改的部分

- `Decision.continue_` 命名 — 公开 API，影响面覆盖所有调用方和测试，收益不足以覆盖风险
- Phase.ACT 条件有效性 — 需将 toolCalls 从 Phase 中分离，涉及类型系统重构，超出本次范围
- `LoopContext` 的 `messages`/`generateOptions` 拆分为不可变配置对象 — 方案 B 的范畴，本次不涉及
- 四阶段状态机架构 — 保持不变
- 所有外部 API（`Agent`、`StreamableAgent`、`CallableAgent` 接口）— 不变
- `LoopExecutor` 中移除不再需要的 `interventionStore` 字段（重构时自然消除）

---

## 六、注释风格

新代码遵循现有 Javadoc 约定：

- 类级：`/** 中文简述。 */` + `<p>` 补充说明
- 字段级：`/** 中文描述 */`
- 公开方法：`/** 中文简述。 */` + `@param` / `@return` 中文说明
- 私有方法：简洁注释说明意图
- 行内注释：仅用于非显而易见的逻辑（如 `+1` 原因、null 兜底）

---

## 七、影响范围

| 模块 | 新增文件 | 修改文件 |
|------|---------|---------|
| agent-core | `InterventionResolver.java`（~180 行） | `LoopExecutor.java`（-120 行）、`LoopContext.java`（+30 行内部类，-20 行字段/getter）、`RequestPipeline.java`（~5 行 Flux.using 改写）、`ModelCallPipeline.java`（2 行）、`ToolCallOrchestrator.java`（2 行，仅内联）、`ReActAgent.java`（~8 行，依赖注入）、`SessionPersistenceHook.java`（4 行） |
| agent-bootstrap | 无 | 无 |
| agent-harness | 无 | 无 |

净效果：LoopExecutor 从 ~670 行减少到 ~500 行，新增 ~180 行可独立测试的 InterventionResolver。

---

## 八、测试策略

- **新增：** `InterventionResolverTest` — 覆盖：
  - 6 种介入状态恢复路径（PENDING/APPROVED/CLARIFIED/DENIED/EXPIRED/req==null）
  - callId 为 null 的防御路径
  - `createIntervention` 的 ctx 状态设置验证
  - 参数回退链（req args → ctx snapshot → empty）
- **更新：** `LoopExecutorTest` — 适配 InterventionResolver mock 注入，恢复路径验证改为验证委托调用
- **更新：** `LoopExecutorInterventionTest` — 同上
- **更新：** `LoopContextTest` — 新增 `InterventionState.clear`/`hasPending` 测试
- **现有测试：** 全部应通过，无行为变化
