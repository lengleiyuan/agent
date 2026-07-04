# 通用人工接管详细方案

日期: 2026-07-04
状态: 待确认

## 架构总览

```
HumanInterventionException（统一触发）
  │
  ▼
LoopExecutor.介入点
  │
  ├── 1. 创建 InterventionRequest → 存入 Store
  ├── 2. ctx.interrupt() 暂停循环
  ├── 3. SessionPersistenceHook 写入 checkpoint（含 interventionId）
  └── 4. 返回 "intervention_required" 信号，HTTP 连接断开
        │
        ▼
[ 人工解决阶段（通过 API/SDK/主管 Agent）]
  GET  /api/interventions/{id}
  POST /api/interventions/{id}/resolve
  
[ 同一 session 下一次请求到达 ]
  RequestPipeline → 加载 checkpoint → 发现 pendingInterventionId
  │
  ├── 检查 InterventionStore.resolve 是否已解决
  │   ├── PENDING → 返回 "still waiting"
  │   ├── APPROVED → apply → 继续循环
  │   ├── DENIED → apply → 继续循环
  │   ├── CLARIFIED → apply modifiedArgs → 继续循环
  │   └── EXPIRED → apply as denied → 继续循环
  │
  └── LoopExecutor.resume() → 继续执行
```

### 关键设计决策

1. **HTTP 断开而非挂起** — 介入可能持续数分钟到数小时，不占连接
2. **checkpoint 保存状态** — 利用已有的 `AgentState` + `SessionPersistenceHook` 持久化机制，增加介入字段
3. **恢复时重新请求** — 客户端轮询或回调触发新请求，框架从 checkpoint 恢复后继续
4. **ApprovalStore 改名 InterventionStore** — 保留现有接口精神，扩展为通用模型
5. **模型完全无感知** — 暂停时对话历史不动，恢复后模型看到正常执行的工具结果或反馈消息

---

## 一、异常模型

### HumanInterventionException

```java
package cd.lan1akea.core.exception;

import cd.lan1akea.core.tool.ToolCallContext;

/**
 * 人工介入异常。工具审批、参数澄清、业务暂停的统一入口。
 * 三种类型：审批（原参数重放）、澄清（修正参数重放）、暂停（注入反馈）。
 * 不可恢复（abort）直接终止。
 */
public class HumanInterventionException extends RuntimeException {

    public enum Type {
        TOOL_APPROVAL,   // 工具审批：原参数重放
        TOOL_CLARIFY,    // 工具澄清：人工修正参数后重放
        BUSINESS_PAUSE   // 业务暂停：注入反馈消息续跑
    }

    private final Type type;
    private final String reason;           // 介入原因（显示给人工）
    private final boolean resumable;       // true=暂停续跑，false=直接终止
    private final String toolName;         // APPROVAL/CLARIFY 时有效
    private final ToolCallContext callParam; // APPROVAL/CLARIFY 时保存的原参数快照

    // ---- 工厂方法 ----

    /** 工具审批：暂停，人工 approve/deny 后原参数重放 */
    public static HumanInterventionException approval(
            String toolName, String question, ToolCallContext callParam) {
        return new HumanInterventionException(
                Type.TOOL_APPROVAL, question, true, toolName, callParam);
    }

    /** 工具澄清：暂停，人工修正参数后重放 */
    public static HumanInterventionException clarify(
            String toolName, String question, ToolCallContext callParam) {
        return new HumanInterventionException(
                Type.TOOL_CLARIFY, question, true, toolName, callParam);
    }

    /** 业务暂停：暂停，人工回复反馈后续跑 */
    public static HumanInterventionException pause(String reason) {
        return new HumanInterventionException(
                Type.BUSINESS_PAUSE, reason, true, null, null);
    }

    /** 不可恢复终止 */
    public static HumanInterventionException abort(String reason) {
        return new HumanInterventionException(
                Type.BUSINESS_PAUSE, reason, false, null, null);
    }

    // ---- getters ----
    public Type getType() { return type; }
    public String getReason() { return reason; }
    public boolean isResumable() { return resumable; }
    public String getToolName() { return toolName; }
    public ToolCallContext getCallParam() { return callParam; }
}
```

### 测试用例

```java
// HumanInterventionExceptionTest

@Test void approval_shouldHaveCorrectType() {
    ToolCallContext ctx = ToolCallContext.of("c1", "transfer", Map.of("amount", 100));
    HumanInterventionException e = HumanInterventionException.approval("transfer", "确认转账?", ctx);
    assertEquals(Type.TOOL_APPROVAL, e.getType());
    assertEquals("transfer", e.getToolName());
    assertEquals("确认转账?", e.getReason());
    assertTrue(e.isResumable());
    assertNotNull(e.getCallParam());
}

@Test void clarify_shouldHaveCorrectType() {
    HumanInterventionException e = HumanInterventionException.clarify("transfer", "收款人?", ctx);
    assertEquals(Type.TOOL_CLARIFY, e.getType());
    assertTrue(e.isResumable());
}

@Test void pause_shouldBeBusinessType() {
    HumanInterventionException e = HumanInterventionException.pause("需要确认");
    assertEquals(Type.BUSINESS_PAUSE, e.getType());
    assertTrue(e.isResumable());
    assertNull(e.getCallParam());
}

@Test void abort_shouldNotBeResumable() {
    HumanInterventionException e = HumanInterventionException.abort("违规");
    assertFalse(e.isResumable());
}
```

---

## 二、持久化模型

### InterventionRequest

```java
package cd.lan1akea.core.intervention;

public class InterventionRequest {

    public enum Type { TOOL_APPROVAL, TOOL_CLARIFY, BUSINESS_PAUSE }
    public enum Status { PENDING, APPROVED, DENIED, CLARIFIED, EXPIRED }

    // ---- 标识 ----
    private final String interventionId;
    private final String sessionId;
    private final String requestId;     // LoopContext.requestId
    private final String tenantId;

    // ---- 介入类型和状态 ----
    private final Type type;
    private volatile Status status;

    // ---- 上下文信息 ----
    private final String agentName;
    private final String toolName;         // APPROVAL/CLARIFY 时有效
    private final String question;         // 向人工提出的问题
    private final String riskLevel;        // LOW/MEDIUM/HIGH/CRITICAL
    private final Map<String, Object> toolArgs;     // 原始工具参数快照（JSON）
    private final List<Msg> recentMessages;         // 暂停时最近消息（截断）

    // ---- 解决信息 ----
    private volatile String resolverId;
    private volatile String resolution;    // 人工回复文本（BUSINESS_PAUSE 时为反馈消息）
    private volatile Map<String, Object> modifiedArgs; // CLARIFY 时的修正参数

    // ---- 时间戳 ----
    private final Instant createdAt;
    private volatile Instant resolvedAt;
    private final Instant expiresAt;       // 默认 createdAt + 5min
    private final int ttlMinutes;          // 默认 5

    // Builder pattern, getters, status transition methods
    public void approve(String resolverId, String resolution) { ... }
    public void deny(String resolverId, String resolution) { ... }
    public void clarify(String resolverId, String resolution, Map<String, Object> modifiedArgs) { ... }
    public void expire() { ... }
    public boolean isExpired() { return Instant.now().isAfter(expiresAt); }
}
```

### InterventionStore

```java
package cd.lan1akea.core.intervention;

public interface InterventionStore {

    /** 创建介入记录，返回 interventionId */
    String create(InterventionRequest req);

    /** 批准 */
    void approve(String interventionId, String resolverId, String comment);

    /** 拒绝 */
    void deny(String interventionId, String resolverId, String comment);

    /** 澄清（带修正参数） */
    void clarify(String interventionId, String resolverId, String comment,
                 Map<String, Object> modifiedArgs);

    /** 按 ID 查询 */
    InterventionRequest getById(String interventionId);

    /** 所有待处理 */
    List<InterventionRequest> getAllPending();

    /** 按会话查询待处理 */
    List<InterventionRequest> getPendingBySession(String sessionId);

    /** 清理过期 */
    void cleanupExpired();
}
```

### AgentState 扩展字段

在现有 `AgentState.java` 中增加：

```java
/** 待解决的介入 ID（null = 无待解决介入） */
private String pendingInterventionId;
/** 介入类型（APPROVAL/CLARIFY/PAUSE，用于恢复时判断逻辑） */
private String interventionType;
/** 暂停时快照的工具参数 JSON（APPROVAL/CLARIFY 时） */
private String pausedToolArgsJson;

// 对应的 getter/setter
```

### 测试用例

```java
// InterventionRequestTest

@Test void build_shouldSetAllFields() {
    InterventionRequest req = InterventionRequest.builder()
        .sessionId("s1").requestId("r1").type(Type.TOOL_APPROVAL)
        .toolName("transfer").question("确认?")
        .toolArgs(Map.of("amount", 100))
        .recentMessages(List.of(msg))
        .ttlMinutes(5).build();
    assertEquals("s1", req.getSessionId());
    assertEquals(Type.TOOL_APPROVAL, req.getType());
    assertEquals(Status.PENDING, req.getStatus());
    assertNotNull(req.getInterventionId());
    assertNotNull(req.getCreatedAt());
}

@Test void approve_shouldTransitionStatus() {
    InterventionRequest req = buildPending();
    req.approve("user1", "同意");
    assertEquals(Status.APPROVED, req.getStatus());
    assertEquals("user1", req.getResolverId());
    assertEquals("同意", req.getResolution());
    assertNotNull(req.getResolvedAt());
}

@Test void deny_shouldTransitionStatus() {
    InterventionRequest req = buildPending();
    req.deny("user1", "拒绝");
    assertEquals(Status.DENIED, req.getStatus());
}

@Test void clarify_shouldStoreModifiedArgs() {
    InterventionRequest req = buildPending();
    Map<String, Object> modified = Map.of("amount", 50);
    req.clarify("user1", "金额减半", modified);
    assertEquals(Status.CLARIFIED, req.getStatus());
    assertEquals(modified, req.getModifiedArgs());
}

@Test void expire_shouldTransitionToExpired() {
    InterventionRequest req = buildPending();
    req.expire();
    assertEquals(Status.EXPIRED, req.getStatus());
}

@Test void isExpired_shouldCheckExpiresAt() {
    InterventionRequest req = InterventionRequest.builder()
        .sessionId("s1").type(Type.BUSINESS_PAUSE).ttlMinutes(0).build();
    assertTrue(req.isExpired());
}

// InMemoryInterventionStoreTest

@Test void createAndGetById() { ... }
@Test void approveAndVerifyStatus() { ... }
@Test void denyAndVerifyStatus() { ... }
@Test void clarifyAndVerifyModifiedArgs() { ... }
@Test void getAllPending_onlyReturnsPending() { ... }
@Test void getPendingBySession_filtersBySession() { ... }
@Test void cleanupExpired_marksAsExpired() { ... }
@Test void concurrentAccess_shouldBeThreadSafe() { ... }
```

---

## 三、主循环改动

### ToolCallOrchestrator 简化

**删除** `handleSuspension()` 方法。不再 catch `HumanInterventionException`。

工具抛 `HumanInterventionException` → 直接穿透 `ToolCallOrchestrator` → 由 `LoopExecutor.executeAct()` 统一 catch。

```java
// executeSingleTool() 中，移除：
// ❌ .onErrorResume(ToolSuspendException.class, e -> handleSuspension(...))

// ToolSuspendException 也不再被抛出，改为 throw HumanInterventionException.approval(...)
```

### LoopExecutor 介入处理

核心改动：`executeAct()` 和 `runStream()` 增加 `onErrorResume(HumanInterventionException)`。

```java
// executeAct() 中：
return Flux.fromIterable(toolCalls)
    .flatMap(tc -> toolOrchestrator.execute(tc, ctx)
        .doOnNext(results::add)
        .map(result -> chunkFromResult(result)))
    .onErrorResume(HumanInterventionException.class, e -> {
        if (!e.isResumable()) return Flux.error(e);
        return handleIntervention(e, ctx, toolCalls);
    })
    .concatWith(...) // rest of executeAct

// runStream() 中（business pause 入口）：
.onErrorResume(HumanInterventionException.class, e -> {
    if (!e.isResumable()) return Flux.error(e);
    return handleIntervention(e, ctx, null);
})

private Flux<ChatStreamChunk> handleIntervention(
        HumanInterventionException e, LoopContext ctx, List<ToolUseBlock> toolCalls) {
    
    // 1. 创建 InterventionRequest
    InterventionRequest req = InterventionRequest.builder()
        .type(toStoreType(e.getType()))
        .sessionId(ctx.getSessionId())
        .requestId(ctx.getRequestId())
        .tenantId(ctx.getTenantId())
        .agentName(ctx.getAgentName())
        .toolName(e.getToolName())
        .question(e.getReason())
        .toolArgs(e.getCallParam() != null
            ? e.getCallParam().getArgumentsMap() : null)
        .recentMessages(truncateMessages(ctx.getMessages(), 20))
        .ttlMinutes(5)
        .build();
    
    String interventionId = interventionStore.create(req);
    
    // 2. 暂停循环
    ctx.setInterventionId(interventionId);
    ctx.setInterventionType(toStoreType(e.getType()).name());
    if (e.getCallParam() != null) {
        ctx.setPausedToolArgs(JsonUtils.toJson(e.getCallParam().getArgumentsMap()));
    }
    ctx.interrupt();
    
    // 3. 发射介入信号 chunk
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("type", "intervention_required");
    payload.put("interventionId", interventionId);
    payload.put("question", e.getReason());
    payload.put("toolName", e.getToolName());
    payload.put("interventionType", e.getType().name());
    
    return Flux.just(ChatStreamChunk.builder()
        .delta(JsonUtils.toJson(payload))
        .type("intervention")
        .finishReason("interrupted")
        .build());
}
```

### LoopContext 扩展

```java
// 新增字段
private volatile String interventionId;
private volatile String interventionType;
private volatile String pausedToolArgs;

// getter / setter
```

### RequestPipeline 恢复逻辑

在 `loadSessionAndHistory()` 中，加载 checkpoint 后检查：

```java
.flatMap(checkpoint -> {
    // 检查是否有未解决的介入
    if (checkpoint.getPendingInterventionId() != null) {
        return handlePendingIntervention(checkpoint, messages);
    }
    // ... existing logic
})

private Mono<List<Msg>> handlePendingIntervention(AgentState state, List<Msg> messages) {
    String id = state.getPendingInterventionId();
    InterventionRequest req = interventionStore.getById(id);
    
    if (req == null || req.getStatus() == Status.EXPIRED) {
        // 已过期，视为拒绝
        state.setPendingInterventionId(null);
        state.setInterventionType(null);
        return stateStore.saveCheckpoint(state).thenReturn(messages);
    }
    
    switch (req.getStatus()) {
        case PENDING:
            // 仍等待 → 返回错误，不继续执行
            return Mono.error(new IllegalStateException(
                "Intervention pending: " + id));
        
        case APPROVED:
            // 工具审批通过 → 标记 approved，继续执行
            // 恢复时 LoopExecutor 从 checkpoint 重建 ToolCallContext
            state.setPendingInterventionId(null);
            state.setInterventionType(null);
            return stateStore.saveCheckpoint(state).thenReturn(messages);
        
        case DENIED:
            // 拒绝 → 注入拒绝消息
            state.setPendingInterventionId(null);
            List<Msg> withDeny = new ArrayList<>(messages);
            withDeny.add(SystemMessage.of("上一步操作被拒绝: " + 
                (req.getResolution() != null ? req.getResolution() : "已拒绝")));
            return stateStore.saveCheckpoint(state).thenReturn(withDeny);
        
        case CLARIFIED:
            // 澄清 → 保存修正参数
            state.setPendingInterventionId(null);
            return stateStore.saveCheckpoint(state).thenReturn(messages);
        
        default:
            return Mono.just(messages);
    }
}
```

### LoopExecutor 恢复入口

`runStream()` 中增加恢复检测：

```java
public Flux<ChatStreamChunk> runStream(LoopContext ctx) {
    return Flux.defer(() -> {
        // 检查是否需要恢复介入
        if (ctx.getInterventionId() != null) {
            InterventionRequest req = interventionStore.getById(ctx.getInterventionId());
            if (req != null && req.getStatus() == Status.APPROVED) {
                return resumeApprovedTool(ctx, req);
            }
            if (req != null && req.getStatus() == Status.CLARIFIED) {
                return resumeClarifiedTool(ctx, req);
            }
        }
        
        if (ctx.isInterrupted()) {
            return handleInterruptStream(ctx);
        }
        // ... normal flow
    });
}

private Flux<ChatStreamChunk> resumeApprovedTool(LoopContext ctx, InterventionRequest req) {
    // 从快照重建 ToolCallContext
    ToolCallContext callParam = rebuildCallParam(req);
    callParam.setApproved(true);
    // 直接重放工具调用
    return toolOrchestrator.executeDirect(callParam, ctx)
        .map(result -> chunkFromResult(result))
        .concatWith(Flux.defer(() -> {
            appendToolResults(ctx, List.of(result));
            return dispatchAfterIteration(ctx)
                .thenMany(Mono.delay(...).flux())
                .thenMany(Flux.defer(() -> runStream(ctx)));
        }));
}
```

### 测试用例（主链路）

```java
// LoopExecutorInterventionTest — 核心链路测试

class LoopExecutorInterventionTest {

    @Mock ChatModel model;
    @Mock ToolExecutor toolExecutor;
    @Mock HookDispatcher hookDispatcher;
    @Mock InterventionStore interventionStore;
    LoopExecutor executor;

    @BeforeEach void setUp() { /* 组装依赖 */ }

    // ===========================================================
    // TOOL_APPROVAL: 触发 → 暂停 → 批准 → 恢复 → 成功
    // ===========================================================

    @Test
    void toolApproval_pauseAndApprove_shouldResumeSuccessfully() {
        // 1. 模型返回 tool_use（调用 transfer）
        when(model.streamWithTools(any(), any(), any()))
            .thenReturn(Flux.just(
                chunkStart("call_1", "transfer"),
                chunkDelta("call_1", "{\"amount\":100}"),
                chunkFinish("tool_calls")))
            .thenReturn(Flux.just(
                chunkText("done"), chunkFinish("stop")));

        // 2. 工具抛审批异常
        when(toolExecutor.execute(any()))
            .thenThrow(HumanInterventionException.approval(
                "transfer", "确认转账100元?", 
                ToolCallContext.of("call_1", "transfer", Map.of("amount", 100))))
            .thenReturn(Mono.just(ToolResult.success("call_1", "已转账")));

        // 3. Store mock
        when(interventionStore.create(any())).thenReturn("int_001");
        InterventionRequest resolved = buildApprovedRequest("int_001");
        when(interventionStore.getById("int_001")).thenReturn(resolved);

        LoopContext ctx = buildCtx("session-1");

        // 第一轮：触发介入 → 发射 intervention_required chunk 后终止
        StepVerifier.create(executor.runStream(ctx))
            .expectNextMatches(c -> "intervention".equals(c.getType())
                && c.getDelta().contains("intervention_required"))
            .verifyComplete();

        // 4. 验证 checkpoint 状态
        assertEquals("int_001", ctx.getInterventionId());
        assertTrue(ctx.isInterrupted());

        // 第二轮（模拟新请求）：检查点有 resolved 的介入 → 恢复执行
        LoopContext ctx2 = rebuildFromCheckpoint(ctx); // 从 AgentState 重建
        when(interventionStore.getById("int_001")).thenReturn(resolved);
        
        // 第二轮 model：工具成功后的最终回复
        when(model.streamWithTools(any(), any(), any()))
            .thenReturn(Flux.just(chunkText("已完成转账"), chunkFinish("stop")));

        StepVerifier.create(executor.runStream(ctx2))
            .expectNextMatches(c -> "已转账".equals(c.getDelta()))     // 工具结果
            .expectNextMatches(c -> "已完成转账".equals(c.getDelta()))  // 模型回复
            .verifyComplete();

        // 验证工具被调用了 2 次（一次抛出、一次重试）
        verify(toolExecutor, times(2)).execute(any());
    }

    // ===========================================================
    // TOOL_APPROVAL: 拒绝
    // ===========================================================

    @Test
    void toolApproval_pauseAndDeny_shouldInjectDenyMessage() {
        when(model.streamWithTools(any(), any(), any()))
            .thenReturn(Flux.just(chunkStart("c1", "transfer"),
                chunkDelta("c1", "{}"), chunkFinish("tool_calls")))
            .thenReturn(Flux.just(chunkText("好的，不转了"), chunkFinish("stop")));

        when(toolExecutor.execute(any()))
            .thenThrow(HumanInterventionException.approval(
                "transfer", "确认?", ToolCallContext.of("c1", "transfer", Map.of())));

        when(interventionStore.create(any())).thenReturn("int_002");
        InterventionRequest denied = buildDeniedRequest("int_002");
        when(interventionStore.getById("int_002")).thenReturn(denied);

        LoopContext ctx = buildCtx("session-2");

        // 第一轮
        StepVerifier.create(executor.runStream(ctx))
            .expectNextMatches(c -> "intervention".equals(c.getType()))
            .verifyComplete();

        // 第二轮：恢复，拒绝 → 工具不重试，模型直接回复
        LoopContext ctx2 = rebuildFromCheckpoint(ctx);
        StepVerifier.create(executor.runStream(ctx2))
            .expectNextMatches(c -> "好的，不转了".equals(c.getDelta()))
            .verifyComplete();

        verify(toolExecutor, times(1)).execute(any()); // 只调了一次（被拒绝，没重试）
    }

    // ===========================================================
    // TOOL_CLARIFY: 澄清（修正参数）
    // ===========================================================

    @Test
    void toolClarify_pauseAndModify_shouldRetryWithModifiedArgs() {
        when(model.streamWithTools(any(), any(), any()))
            .thenReturn(Flux.just(chunkStart("c1", "transfer"),
                chunkDelta("c1", "{\"amount\":100}"), chunkFinish("tool_calls")))
            .thenReturn(Flux.just(chunkText("已转账"), chunkFinish("stop")));

        // 第一次抛出 clarify（参数不明确）
        when(toolExecutor.execute(any()))
            .thenThrow(HumanInterventionException.clarify(
                "transfer", "收款人不明确",
                ToolCallContext.of("c1", "transfer", Map.of("amount", 100))))
            // 第二次用修正参数重试
            .thenReturn(Mono.just(ToolResult.success("c1", "已转账给张三100元")));

        when(interventionStore.create(any())).thenReturn("int_003");
        InterventionRequest clarified = buildClarifiedRequest("int_003",
            Map.of("amount", 100, "receiver", "张三"));
        when(interventionStore.getById("int_003")).thenReturn(clarified);

        LoopContext ctx = buildCtx("session-3");

        // 第一轮
        StepVerifier.create(executor.runStream(ctx))
            .expectNextMatches(c -> "intervention".equals(c.getType()))
            .verifyComplete();

        // 第二轮：clarified 恢复，用修正参数重试
        LoopContext ctx2 = rebuildFromCheckpoint(ctx);
        StepVerifier.create(executor.runStream(ctx2))
            .expectNextMatches(c -> c.getDelta().contains("张三"))
            .expectNextMatches(c -> "已转账".equals(c.getDelta()))
            .verifyComplete();

        verify(toolExecutor, times(2)).execute(any());
    }

    // ===========================================================
    // BUSINESS_PAUSE: 人工反馈后续跑
    // ===========================================================

    @Test
    void businessPause_resumeWithFeedback_shouldContinue() {
        // Hook 抛出 pause
        when(hookDispatcher.dispatch(any(), any()))
            .thenReturn(Mono.just(HookResult.continue_()))
            .thenThrow(HumanInterventionException.pause("检测异常，需确认"));

        when(interventionStore.create(any())).thenReturn("int_004");
        InterventionRequest resolved = buildResolvedPause("int_004", "继续执行");
        when(interventionStore.getById("int_004")).thenReturn(resolved);

        when(model.streamWithTools(any(), any(), any()))
            .thenReturn(Flux.just(chunkText("好，继续"), chunkFinish("stop")));

        LoopContext ctx = buildCtx("session-4");

        // 第一轮：pause
        StepVerifier.create(executor.runStream(ctx))
            .expectNextMatches(c -> "intervention".equals(c.getType()))
            .verifyComplete();

        // 第二轮：恢复，paused feedback 注入为消息
        LoopContext ctx2 = rebuildFromCheckpoint(ctx);
        // 恢复后应该有 feedback 消息在 messages 中
        StepVerifier.create(executor.runStream(ctx2))
            .expectNextCount(1)
            .verifyComplete();
    }

    // ===========================================================
    // 过期
    // ===========================================================

    @Test
    void interventionExpired_shouldTreatAsDenied() {
        when(toolExecutor.execute(any()))
            .thenThrow(HumanInterventionException.approval(
                "transfer", "确认?", ToolCallContext.of("c1", "transfer", Map.of())));

        when(interventionStore.create(any())).thenReturn("int_005");
        when(interventionStore.getById("int_005")).thenReturn(null); // 过期被清理

        LoopContext ctx = buildCtx("session-5");

        StepVerifier.create(executor.runStream(ctx))
            .expectNextMatches(c -> "intervention".equals(c.getType()))
            .verifyComplete();

        // 第二轮：过期 → 视为拒绝
        LoopContext ctx2 = rebuildFromCheckpoint(ctx);
        StepVerifier.create(executor.runStream(ctx2))
            .verifyComplete();
    }

    // ===========================================================
    // 待处理（未解决）→ 不继续
    // ===========================================================

    @Test
    void interventionStillPending_shouldNotResume() {
        // ... 第一轮触发介入后
        // 第二轮：介入仍 PENDING
        LoopContext ctx2 = rebuildWithPendingIntervention(ctx);
        assertThrows(IllegalStateException.class, () -> {
            pipeline.execute(messages, runtimeCtx).block();
        }); // 或返回特定错误响应
    }

    // ===========================================================
    // 不可恢复（abort）
    // ===========================================================

    @Test
    void abortIntervention_shouldTerminateImmediately() {
        when(hookDispatcher.dispatch(any(), any()))
            .thenThrow(HumanInterventionException.abort("违规内容"));

        LoopContext ctx = buildCtx("session-6");
        StepVerifier.create(executor.runStream(ctx))
            .verifyError(HumanInterventionException.class);
        verify(interventionStore, never()).create(any()); // abort 不创建记录
    }

    // ===========================================================
    // 不同 session 并发不受影响
    // ===========================================================

    @Test
    void differentSessions_shouldNotBlockEachOther() {
        // Session A 触发介入暂停，Session B 正常执行
    }

    // ---- helpers ----

    private LoopContext buildCtx(String sessionId) {
        return LoopContext.builder()
            .agentName("test").sessionId(sessionId)
            .messages(List.of(UserMessage.of("hi")))
            .generateOptions(GenerateOptions.defaults())
            .stream(true).build();
    }
}
```

---

## 四、主管工具

### ResolveInterventionTool

```java
public class ResolveInterventionTool extends ToolBase {
    
    public ResolveInterventionTool(InterventionStore store) {
        declareStringParam("intervention_id", "介入记录ID", true);
        declareStringParam("action", "操作: approve/deny/clarify/reply", true);
        declareStringParam("comment", "备注", false);
        declareObjectParam("modified_args", "修正后的参数(仅clarify需要)", false);
    }

    @Override public String getName() { return "resolve_intervention"; }

    @Override
    public Mono<ToolResult> execute(ToolCallContext params) {
        String id = params.getString("intervention_id");
        String action = params.getString("action");
        String comment = params.getString("comment");

        InterventionRequest req = store.getById(id);
        if (req == null) return fail("介入记录不存在: " + id);
        if (req.getStatus() != Status.PENDING) return fail("已处理");

        switch (action.toLowerCase()) {
            case "approve":
                store.approve(id, params.getUserId(), comment);
                return success("已批准: " + req.getQuestion());
            case "deny":
                store.deny(id, params.getUserId(), comment);
                return success("已拒绝: " + req.getQuestion());
            case "clarify":
                Map<String, Object> modified = params.get("modified_args");
                store.clarify(id, params.getUserId(), comment, modified);
                return success("已澄清: " + req.getQuestion());
            case "reply":
                store.approve(id, params.getUserId(), comment); // PAUSE 场景的回
                return success("已回复: " + comment);
            default:
                return fail("无效操作: " + action);
        }
    }
}
```

### 测试用例

```java
// ResolveInterventionToolTest

@Test void approve_shouldCallStoreApprove() { ... }
@Test void deny_shouldCallStoreDeny() { ... }
@Test void clarify_shouldCallStoreClarify_withModifiedArgs() { ... }
@Test void reply_shouldCallStoreApprove_forPause() { ... }
@Test void notFound_shouldReturnFailure() { ... }
@Test void alreadyResolved_shouldReturnFailure() { ... }
@Test void missingRequiredParam_shouldValidateParams() { ... }
```

---

## 五、清理项

| 操作 | 文件 | 理由 |
|------|------|------|
| 删除 | `tool/ToolSuspendException.java` | 统一到 HumanInterventionException.approval() |
| 删除 | `ToolCallOrchestrator.handleSuspension()` | 介入逻辑移到 LoopExecutor |
| 删除 | `approval/ApprovalHook.java` | 介入记录创建移到 LoopExecutor |
| 删除 | `approval/PendingApproval.java` | 替换为 InterventionRequest |
| 删除 | `approval/ApprovalStore.java` | 替换为 InterventionStore |
| 删除 | `approval/InMemoryApprovalStore.java` | 替换为 InMemoryInterventionStore |
| 修改 | `ApproveApprovalTool.java` | 改为 ResolveInterventionTool |
| 修改 | `ListApprovalsTool.java` | 改为 ListInterventionsTool |
| 修改 | `ToolExecutor.java` | 移除 approvalStore 引用 |
| 修改 | `HarnessAgent.java` | 替换 ApprovalStore → InterventionStore |

---

## 六、文件清单

| 操作 | 文件 |
|------|------|
| 新增 | `exception/HumanInterventionException.java` |
| 新增 | `intervention/InterventionRequest.java` |
| 新增 | `intervention/InterventionStore.java` |
| 新增 | `intervention/InMemoryInterventionStore.java` |
| 新增 | `tool/builtin/ResolveInterventionTool.java` |
| 新增 | `tool/builtin/ListInterventionsTool.java` |
| 修改 | `agent/loop/LoopExecutor.java` (+ catch + handleIntervention + resume) |
| 修改 | `agent/loop/LoopContext.java` (+ interventionId/type/pausedArgs) |
| 修改 | `agent/loop/RequestPipeline.java` (+ handlePendingIntervention) |
| 修改 | `agent/loop/ToolCallOrchestrator.java` (- handleSuspension) |
| 修改 | `state/AgentState.java` (+3 字段) |
| 修改 | `approval/ApprovalController.java` → `intervention/InterventionController.java` |
| 删除 | `tool/ToolSuspendException.java` |
| 删除 | `approval/ApprovalHook.java` |
| 删除 | `approval/PendingApproval.java` |
| 删除 | `approval/ApprovalStore.java` |
| 删除 | `approval/InMemoryApprovalStore.java` |

---

## 七、测试清单（合计 ~30 用例）

| 测试类 | 用例数 | 覆盖内容 |
|--------|--------|---------|
| `HumanInterventionExceptionTest` | 6 | 工厂方法、类型、字段、resumable |
| `InterventionRequestTest` | 8 | builder、状态转换、过期检查 |
| `InMemoryInterventionStoreTest` | 10 | CRUD、查询、并发、过期清理 |
| `LoopExecutorInterventionTest` | 8 | 主链路：审批/澄清/暂停/拒绝/过期 |
| `ResolveInterventionToolTest` | 6 | 四种 action、参数校验 |
| `ListInterventionsToolTest` | 3 | 空列表、单条、多条 |
| `RequestPipelineInterventionTest` | 2 | checkpoint 恢复、pending 拒绝 |
