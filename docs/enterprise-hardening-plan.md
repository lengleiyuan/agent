# 企业级加固方案

> 生成日期：2026-07-02  
> 目标：将框架从 Demo/PoC 级提升至可承载生产流量的企业级水平  
> 原则：不破坏现有接口签名、不改变 Builder API、不改动 core 层抽象边界

---

## 一、整体策略

### 改动分级

| 级别 | 说明 | 风险 |
|------|------|------|
| **P0 - 零破坏性修复** | 纯内部 bug 修复，不动任何接口 | 低 |
| **P1 - 新 SPI/切点** | 新增接口，默认 null/空实现，不启用无感知 | 低 |
| **P2 - 核心循环手术** | 需修改 ReActLoop / ReActAgent 内部逻辑 | 中 |
| **P3 - 周边补全** | Bootstrap 层新增，不碰 core | 低 |

### 每个修复项的判定标准

- **修改哪个文件，哪个方法**
- **改前代码 vs 改后代码**
- **为什么这样改，为什么不那样改**
- **影响范围**
- **需要补什么测试**

---

## 二、P0 — 零破坏性 Bug 修复

### 2.1 RateLimitHook 窗口切换竞态

**文件**：`agent-core/src/main/java/cd/lan1akea/core/hook/impl/RateLimitHook.java`

**问题**：`windowStart` 的 check-then-act 非原子，两个线程同时检测到窗口过期会互相覆盖计数。

**改后**：用一个不可变 record `Window(long startMs, AtomicInteger count)` 整体 CAS。

```java
private record Window(long startMs, AtomicInteger count) {}
private final AtomicReference<Window> window = new AtomicReference<>(
    new Window(System.currentTimeMillis(), new AtomicInteger(0)));

@Override
public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
    long now = System.currentTimeMillis();
    Window w = window.get();
    if (now - w.startMs > windowMs) {
        Window newWindow = new Window(now, new AtomicInteger(0));
        window.compareAndSet(w, newWindow);
        w = window.get(); // 无论是哪个线程赢了，都用最新的 Window
    }
    int count = w.count.incrementAndGet();
    if (count > maxCallsPerWindow) {
        return Mono.just(HookResult.abort(
            "工具调用频率超限: " + count + "/" + maxCallsPerWindow
            + " 每 " + (windowMs / 1000) + " 秒"));
    }
    return Mono.just(HookResult.continue_());
}
```

**关键决策**：不用 `synchronized` 或 `Lock`——Reactor 事件循环中阻塞是反模式。CAS 失败后直接取当前值（`w = window.get()`），容忍一个窗口内多 1 个请求，比加锁好。

**影响范围**：仅此文件。`RateLimitHook` 公共 API 不变。

**测试**：`RateLimitHook` 并发测试——N 个线程同时调用，验证 maxCallsPerWindow 约束不被突破（允许 ±1 的竞争误差）。

---

### 2.2 InMemoryAgentStateStore.addTurn 线程安全

**文件**：`agent-core/src/main/java/cd/lan1akea/core/state/InMemoryAgentStateStore.java`

**问题**：`session.addTurn()` 写普通 `ArrayList`，并发 `addTurn` 丢数据。

**改后**：Session 内部 `turns` 改为线程安全集合。

```java
// Session.java
private final List<ChatTurn> turns;

// 构造中
this.turns = turns != null 
    ? Collections.synchronizedList(new ArrayList<>(turns)) 
    : Collections.synchronizedList(new ArrayList<>());
```

**为什么不用 CopyOnWriteArrayList**：`addTurn()` 比遍历更频繁，COW 写时复制开销大。`synchronizedList` 适合写操作频次适中的场景。

**影响范围**：`Session.java` 1 行。`getTurns()` 返回 `Collections.unmodifiableList` 不变，但底层已同步。

**测试**：并发 `addTurn` + 遍历验证不抛 `ConcurrentModificationException`。

---

### 2.3 ChatModelBase.isRetryable 不覆盖 IO 异常

**文件**：`agent-core/src/main/java/cd/lan1akea/core/model/ChatModelBase.java`

**问题**：网络超时、连接断开、DNS 解析失败不重试。

**改后**：

```java
protected boolean isRetryable(Throwable e) {
    if (e instanceof ModelException) {
        int status = ((ModelException) e).getHttpStatus();
        return status == 429 || status >= 500;
    }
    // IO 异常一般是瞬时的：超时、连接断开、连接池耗尽
    if (e instanceof java.io.IOException) return true;
    if (e instanceof java.util.concurrent.TimeoutException) return true;
    // Reactor Netty 的包装异常
    if (e instanceof reactor.netty.ReactorNetty.InternalNettyException) return true;
    // 兜底：解包 cause 链
    Throwable cause = e;
    while (cause != null) {
        if (cause instanceof java.io.IOException) return true;
        if (cause instanceof java.util.concurrent.TimeoutException) return true;
        cause = cause.getCause();
    }
    return false;
}
```

**为什么不全量重试**：400/401/403 这种客户端错误重试无意义且浪费重试配额。

**影响范围**：仅此方法。`maxRetries=3` 保护不会无限重试。

**测试**：模拟 `java.net.SocketTimeoutException` 和 `java.net.ConnectException` 验证重试触发。

---

### 2.4 summarize() 可能触发工具调用

**文件**：`agent-core/src/main/java/cd/lan1akea/core/agent/loop/ReActLoop.java`  
**方法**：`summarize()` 和 `summarizeStream()`

**问题**：传了 `toolSchemas` 但 `toolChoice` 未设为 NONE，模型可能继续返回 tool call。

**改后**：

```java
GenerateOptions noTools = GenerateOptions.builder()
    .temperature(ctx.getGenerateOptions().getTemperature())
    .maxTokens(Math.min(ctx.getGenerateOptions().getMaxTokens(), 1024))
    .toolChoice(ToolChoicePolicy.NONE)  // ← 加这一行
    .build();
```

**影响范围**：两处各加 1 行。summarize 和 summarizeStream。

---

### 2.5 流式路径缺少超时控制

**文件**：`agent-core/src/main/java/cd/lan1akea/core/agent/ReActAgent.java`  
**方法**：`doStream()`

**问题**：`doChat()` 有 `.timeout(totalTimeoutMs)`，`doStream()` 没有。

**改后**：在 `doStream()` 返回的 Flux 上加超时。

```java
return aroundHookChain.aroundCallStream(...)
    .map(e -> ...)
    .contextWrite(c -> writeContext(c, ctx))
    .timeout(Duration.ofMillis(totalTimeoutMs > 0 ? totalTimeoutMs : 300_000));  // ← 加超时
```

由于 Flux 超时会触发 `TimeoutException`，需要在 `ReActLoop` 的错误处理中识别它并返回人类可读的超时消息。

**注意**：流式场景的 `totalTimeoutMs` 应该是从上一次收到 chunk 的超时（空闲超时），而非总超时。建议用 `idleTimeout` 配置项替代。此处先以总超时兜底。

---

### 2.6 ToolRegistry.adapters 和 AroundHookChain.hooks 非线程安全

**文件**：
- `agent-core/src/main/java/cd/lan1akea/core/tool/ToolRegistry.java:50`
- `agent-core/src/main/java/cd/lan1akea/core/hook/AroundHookChain.java:28`

**问题**：普通 `ArrayList`，运行时如果动态 register 并发遍历会 CME。

**改后**：

```java
// ToolRegistry.java:50
private final List<ToolAdapter> adapters = new CopyOnWriteArrayList<>();

// AroundHookChain.java:28
private final List<AroundHook> hooks = new CopyOnWriteArrayList<>();
```

`register()` 直接 `add()`（COW 线程安全），`adaptToAll()` 遍历用增强 for（COW 的 snapshot 语义保证不抛 CME）。注意 `AroundHookChain.wrap()` 构建 Function 链时遍历 hooks 是 O(n)，COW 的 snapshot 语义不影响正确性。

---

### 2.7 InMemoryApprovalStore 无后台清理导致内存泄漏

**文件**：`agent-core/src/main/java/cd/lan1akea/core/approval/InMemoryApprovalStore.java`

**问题**：只在 `getPendingBySession()` / `getAllPending()` 时被动触发 `evictExpired()`。如果从不查询，过期审批永驻内存。

**改后**：追加一个主动清理方法，并在 `ApprovalStore` 接口加 default method：

```java
// ApprovalStore.java — 新 default method
default void cleanupExpired() {}

// InMemoryApprovalStore.java — 覆盖
@Override
public void cleanupExpired() {
    evictExpired();
}
```

Bootstrap 层通过 `@Scheduled` 定时调用：

```java
// agent-bootstrap config
@Scheduled(fixedRate = 60_000)  // 每分钟清理
public void cleanupApprovals() {
    approvalStore.cleanupExpired();
}
```

**注意**：`evictExpired()` 内部对 ConcurrentHashMap 的 entrySet 遍历是弱一致的，cleanup 过程 new 的过期项可能不被当前迭代找到，下次清理会处理，可接受。

---

## 三、P1 — 新 SPI / 切点

### 3.1 RequestId 全链路追踪

**新增字段**：

```
RuntimeContext.Builder
  + requestId(String)         // 新增，默认 UUID.randomUUID()
```

**改动文件**：

| 文件 | 改动 |
|------|------|
| `RuntimeContext.java` | 加 `requestId` 字段 + getter + Builder 方法 |
| `LoopContext.java` | 加 `requestId` 字段，`fromRuntimeContext()` 自动复制 |
| `HookContext.java` | 加 `requestId` 字段，构造时从 LoopContext 取 |
| `ReActAgent.java` | `resolveRuntimeContext()` 中若未传入 requestId 则自动生成 |

**LoggingHook 增强**：日志格式从 `"session=" + ctx.getSessionId()` 改为 `"rid={} sid={}"`。

**不改动**：`Agent` / `CallableAgent` / `StreamableAgent` 接口签名不变。requestId 对调用方完全透明。

---

### 3.2 AgentMetrics SPI

**新增接口**（`agent-core/src/main/java/cd/lan1akea/core/metrics/AgentMetrics.java`）：

```java
package cd.lan1akea.core.metrics;

/**
 * Agent 指标收集 SPI。
 * 实现类可接入 Micrometer / Prometheus / OpenTelemetry。
 * 默认为 NoopAgentMetrics（无操作）。
 */
public interface AgentMetrics {

    /** LLM 调用完成 */
    void recordLlmCall(String model, String provider, long latencyMs,
                       int promptTokens, int completionTokens, boolean success, String errorType);

    /** 工具调用完成 */
    void recordToolCall(String toolName, String riskLevel, long latencyMs,
                        boolean success, boolean approved, String errorType);

    /** 一次 ReAct 迭代完成 */
    void recordIteration(String agentName, String sessionId, int iteration,
                         int toolCallsThisIteration);

    /** 审批事件 */
    void recordApproval(String toolName, String sessionId, String decision);

    /** token 消耗记录 */
    void recordTokenUsage(String agentName, String sessionId, String model,
                          int promptTokens, int completionTokens);

    /** Agent 级别 noop 实例 */
    AgentMetrics NOOP = new AgentMetrics() {
        public void recordLlmCall(String m, String p, long l, int pt, int ct, boolean s, String e) {}
        public void recordToolCall(String t, String r, long l, boolean s, boolean a, String e) {}
        public void recordIteration(String a, String s, int i, int tc) {}
        public void recordApproval(String t, String s, String d) {}
        public void recordTokenUsage(String a, String s, String m, int p, int c) {}
    };
}
```

**埋点位置**：

| 埋点 | 文件:方法 | 记录内容 |
|------|-----------|----------|
| LLM 调后 | `ReActLoop.reasoning()` 中 `model.chatWithTools()` 之后 | 模型名、延迟、token、成败 |
| 工具调后 | `ReActLoop.executeSingleTool()` 中 `toolExecutor.execute()` 之后 | 工具名、风险级别、延迟、成败、审批 |
| 迭代后 | `ReActLoop.execute()` 一次迭代完成时 | 迭代数、该轮工具调用数 |
| 审批事件 | `ApprovalHook.onEvent()` 中 | 工具名、决策 |

**注入方式**：

```java
// ReActAgent 中
private AgentMetrics metrics = AgentMetrics.NOOP;
public void setMetrics(AgentMetrics m) { this.metrics = m; }

// HarnessAgent.Builder 中
public Builder metrics(AgentMetrics m) { /* 透传给 ReActAgent */ }
```

**Micrometer 实现**放在 `agent-bootstrap` 模块或独立模块，不污染 core。

---

### 3.3 Circuit Breaker — ChatModel 装饰器

**新增类**：`agent-core/src/main/java/cd/lan1akea/core/model/ResilienceChatModel.java`

```java
package cd.lan1akea.core.model;

/**
 * ChatModel 装饰器，提供熔断保护。
 *
 * 状态机：CLOSED → (失败次数达阈值) → OPEN → (等待 halfOpenAfter) → HALF_OPEN
 *         HALF_OPEN → (试探成功) → CLOSED
 *         HALF_OPEN → (试探失败) → OPEN
 *
 * OPEN 状态下所有请求直接失败，不穿透到下游。
 */
public class ResilienceChatModel implements ChatModel {

    private final ChatModel delegate;
    private final int failureThreshold;     // 默认 5
    private final long halfOpenAfterMs;     // 默认 30_000
    private final AtomicReference<CircuitState> state = ...;

    // 所有 ChatModel 方法委托给 delegate，包裹错误计数逻辑
}
```

**为什么不改 ChatModelBase**：装饰器模式保持单一职责。`ChatModelBase` 只负责 API 调用和重试，熔断是独立关注点。已有的 `DynamicChatModel` 也是装饰器——保持这个模式。

**HarnessAgent.Builder 启用**：

```java
public Builder circuitBreaker(boolean enable) { ... }
```

默认 false（不启用），不改变现有行为。

---

### 3.4 TokenEstimator 可替换实现

**现有接口**：`agent-core/src/main/java/cd/lan1akea/core/model/TokenEstimator.java`

**现状**：`CharBasedTokenEstimator` 用 `charCount / 4` 估算，中文误差大。

**无需新增切点**——接口已存在。只需提供 `TikTokenEstimator`：

```java
/**
 * 基于 cl100k_base 编码器的精确 Token 计数。
 * 对齐 OpenAI tiktoken 的编码逻辑。
 * 纯 Java 实现，无原生依赖。
 */
public class Cl100kTokenEstimator implements TokenEstimator {
    // 实现 BPE 编码，或内嵌编码表
}
```

`ContextCompressionHook` 构造时注入 `TokenEstimator`。HarnessAgent.Builder 默认使用 `CharBasedTokenEstimator`，可覆盖。

**权衡**：纯 Java 的 BPE 实现（无 JNI）对长文本性能不如原生 tiktoken，但避免了 C++/Rust 依赖的部署复杂度。更务实的方案：在 `TokenEstimator` 中加一个 `estimateBatch(List<Msg>)` 方法，TikToken 实现时一次性编码所有消息而非逐条调用。

---

### 3.5 McpClient 健康检查与重连

**切入点**：`McpClient` 接口已实现 `AutoCloseable`，需增强。

**新增**：

```java
// McpClient.java
public Mono<Boolean> healthCheck() {
    // 发送 ping 或空 RPC 调用验证连接存活
    return rpcCall("ping", Map.of())
        .map(r -> true)
        .onErrorReturn(false);
}

public Mono<Void> reconnect() {
    return transport.closeAsync()
        .then(transport.initialize());
}
```

**McpTransport 接口增强**：加 `Mono<Void> closeAsync()`（已有 `close()` 同步版本），加超时参数。

**集成到 HarnessAgent**：在 `HarnessAgent` 构造中增加 `ScheduledExecutorService` 定期健康检查，发现断开自动重连。`shutdown()` 时取消定时任务。

**改动范围**：`McpClient` 加 2 个方法，`McpTransport` 接口加 1 个 default method，`HarnessAgent` 内部加定时逻辑。不破坏现有 MCP 工具注册流程。

---

## 四、P2 — 核心循环手术

### 4.1 流式路径 Hook fire-and-forget → 正确错误传播

**这是风险最高的改动。需要最仔细的设计和测试。**

**问题代码**（三处）：

```java
// ReActLoop.java:241-243 — reasoningStream
.doOnComplete(() ->
    hookDispatcher.dispatch(HookEventType.POST_MODEL_CALL, ...).subscribe());

// ReActLoop.java:245-247 — reasoningStream
.doOnComplete(() -> {
    hookDispatcher.dispatch(HookEventType.POST_REASONING, ...).subscribe();
});

// ReActLoop.java:408 — dispatchAfterIteration
hookDispatcher.dispatch(HookEventType.AFTER_ITERATION, event, hc).subscribe();
```

**改造原则**：把 fire-and-forget 替换为链式操作，Hook 失败 → 流终止 → 错误传播到调用方。

**改动详解**：

#### reasoningStream() 改造

**改前**结构：

```java
return hookDispatcher.dispatch(PRE_REASONING, pre, hc)
    .flatMapMany(r -> {
        // ... 早退逻辑 ...
        return hookDispatcher.dispatch(PRE_MODEL_CALL, ...)
            .flatMapMany(mr -> ...model.streamWithTools...)
            .doOnComplete(() -> dispatch(POST_MODEL_CALL).subscribe()); // ❌
    })
    .doOnComplete(() -> dispatch(POST_REASONING).subscribe()); // ❌
```

**改后**结构：

```java
return hookDispatcher.dispatch(PRE_REASONING, pre, hc)
    .flatMapMany(r -> {
        // ... 早退逻辑 ...
        return hookDispatcher.dispatch(PRE_MODEL_CALL, ...)
            .flatMapMany(mr -> aroundHookChain.aroundReasoningStream(pre, hc,
                e -> model.streamWithTools(...)))
            .concatWith(Mono.defer(() ->
                hookDispatcher.dispatch(POST_MODEL_CALL, new HookEvent(POST_MODEL_CALL), hc)
                    .then(Mono.empty())     // 成功则继续
                    .onErrorMap(e -> ...)   // 失败则传播错误
            ));
    })
    .concatWith(Mono.defer(() ->
        hookDispatcher.dispatch(POST_REASONING, new ReasoningEvent(POST_REASONING), hc)
            .then(Mono.empty())
    ));
```

**关键风险**：`concatWith(Mono.defer(...).then(Mono.empty()))` — 如果 POST hook 失败（如 SessionPersistenceHook 写 DB 失败），错误会终止整个 Flux。调用方收到 error 信号而非静默吞掉。这是**期望行为**——持久化失败不应该静默。

**但是**：这改变了语义。之前持久化失败被忽略（用户无感知），现在持久化失败会中断响应流。需要在 `SessionPersistenceHook` 中加 `onErrorResume` 包装，把持久化失败降级为日志告警而非中断。

#### dispatchAfterIteration 改造

**改前**（非流式路径中也存在类似问题）：

```java
// ReActLoop.java:101-103
private void dispatchAfterIteration(LoopContext ctx) {
    ...
    hookDispatcher.dispatch(HookEventType.AFTER_ITERATION, event, hc).subscribe(); // ❌
}
```

**改后**：改为返回 `Mono<Void>`，在调用处链入：

```java
// 非流式 execute()
return acting(ctx, toolCalls)
    .flatMap(results -> {
        appendToolResults(ctx, results);
        ctx.setIteration(ctx.getIteration() + 1);
        return dispatchAfterIteration(ctx).thenReturn(ctx);  // ← 链入
    })
    .flatMap(this::execute);

// 流式 executeStream()
.concatWith(dispatchAfterIteration(ctx).then(Mono.empty()))
.concatWith(executeStream(ctx));
```

**改后签名**：

```java
private Mono<Void> dispatchAfterIteration(LoopContext ctx) {
    HookContext hc = buildHookContext(ctx);
    HookEvent event = new HookEvent(HookEventType.AFTER_ITERATION);
    event.setPayload("loopContext", ctx);
    return hookDispatcher.dispatch(HookEventType.AFTER_ITERATION, event, hc)
        .onErrorResume(e -> {
            // AFTER_ITERATION hook 失败不中断主流程，只记日志
            log.warn("AFTER_ITERATION hook failed", e);
            return Mono.empty();
        })
        .then();
}
```

**设计决策**：`AFTER_ITERATION` 的 Hook 失败不应中断对话。与 POST_MODEL_CALL 不同——前者是"持久化已完成的迭代"，后者是"模型调用后的后处理"。如果持久化失败，告警即可（`onErrorResume` 吞掉），不要让用户看到底层存储错误。

**测试**：这是最需要补测试的改动。至少需要：
1. POST_MODEL_CALL hook 抛出异常 → 流错误终止 → 客户端收到 error
2. AFTER_ITERATION hook 抛出异常 → 流正常继续 → 日志中有告警
3. 无 Hook 场景 → 行为不变
4. 多迭代场景 → POST hook 在每次迭代后正确触发

---

### 4.2 activeLoopContext 单槽 → 并发请求支持

**问题**：`AtomicReference<LoopContext>` 只存一个当前请求。同一 Agent 并发请求下 `interrupt()` 行为不确定。

**改后**：

```java
// ReActAgent.java
private final ConcurrentHashMap<String, LoopContext> activeRequests = new ConcurrentHashMap<>();

// doChat() / doStream() 中
String requestId = loopCtx.getRequestId(); // 来自 RuntimeContext
activeRequests.put(requestId, loopCtx);
Mono<ChatResponse> exec = reActLoop.execute(loopCtx)
    .doFinally(s -> activeRequests.remove(requestId));
```

**interrupt 改造**：

```java
// ReActAgent.java — 中断所有活跃请求
@Override
public void interrupt() {
    for (LoopContext ctx : activeRequests.values()) {
        ctx.interrupt();
    }
}

// 按 sessionId 中断（企业场景更常用）
public void interruptBySession(String sessionId) {
    for (LoopContext ctx : activeRequests.values()) {
        if (sessionId.equals(ctx.getSessionId())) ctx.interrupt();
    }
}
```

**`interrupt(Msg)` 同样**：在 Msg 的 metadata 中设 target sessionId 或 broadcastToAll。

**影响范围**：
- `ReActAgent.java`：`activeLoopContext` → `activeRequests`，约 15 行
- `AgentController.java`：`/interrupt` 端点不变（当前是 broadcast 中断）
- `Agent` 接口不变

---

### 4.3 迭代间退避策略

**问题**：如果 LLM 反复返回相同 tool call（模型幻觉常见场景），ReActLoop 会以最快速度 spin，烧 token。

**改后**：

```java
// AgentExecutionConfig.java — 新增字段
private long iterationBackoffMs = 0;  // 默认 0，不启用

// ReActLoop.execute() — 递归尾部
return acting(ctx, toolCalls)
    .flatMap(results -> {
        appendToolResults(ctx, results);
        ctx.setIteration(ctx.getIteration() + 1);
        return dispatchAfterIteration(ctx).thenReturn(ctx);
    })
    .delayElement(Duration.ofMillis(ctx.getBackoffMs()))  // ← 加退避
    .flatMap(this::execute);
```

**退避策略**：线性退避 `min(iteration * 100, 2000)` 或固定延迟。默认 0 不改现有行为。

**HarnessAgent.Builder** 暴露：`.iterationBackoffMs(long)`。

---

## 五、P3 — Bootstrap 层补全

### 5.1 API 层限流

**新增**：`agent-bootstrap/.../config/ApiRateLimitFilter.java`

```java
@Component
@WebFilter("/api/**")
public class ApiRateLimitFilter implements WebFilter {
    // per-tenant 令牌桶
    // 默认 60 req/min per tenant
    // 响应 429 + Retry-After header
}
```

纯 Spring Boot 层，不入侵 core。可与 `RateLimitHook` 共存——前者限 API 调用频率，后者限工具调用频率。

---

### 5.2 历史消息分页

**改动**：`AgentController.history()` 加 `@RequestParam offset, limit`

```java
@GetMapping("/session/{sessionId}/history")
public Flux<Msg> history(
    @PathVariable String sessionId,
    @RequestParam(defaultValue = "0") int offset,
    @RequestParam(defaultValue = "50") int limit) {
    return stateStore.getHistory(new SessionId(sessionId))
        .skip(offset).take(limit);
}
```

`AgentStateStore` 接口加 `Flux<Msg> getHistory(SessionId, int offset, int limit)` default method，默认实现基于现有 `getHistory()` + skip/take。

---

### 5.3 MCP 健康检查定时任务

```java
// agent-bootstrap config
@Configuration
@EnableScheduling
public class McpHealthCheckConfig {

    private final List<McpClient> clients;

    @Scheduled(fixedRate = 30_000)
    public void checkMcpConnections() {
        for (McpClient client : clients) {
            client.healthCheck()
                .filter(healthy -> !healthy)
                .flatMap(__ -> client.reconnect())
                .doOnError(e -> log.warn("MCP reconnect failed", e))
                .subscribe();
        }
    }
}
```

---

### 5.4 审批清理定时任务

```java
// agent-bootstrap config
@Scheduled(fixedRate = 60_000)
public void cleanupExpiredApprovals() {
    approvalStore.cleanupExpired();
}
```

---

## 六、不修改的边界

以下**故意不改**，因为它们属于框架外的职责：

| 项 | 说明 |
|----|------|
| 认证/授权 | 由 API Gateway（Kong/APISIX）或 Spring Security 在框架外部完成。框架只需从 Header 中**
```plaintext
tenantId/userId。框架不应承担身份认证职责。
```
| 租户数据隔离的强制校验 | 框架提供四级作用域机制，但不校验"tenant-A 的用户是否伪造了 tenant-B 的 sessionId"。这应由 API Gateway 层面的身份绑定保证。 |
| 分布式会话存储 | `AgentStateStore` 已有接口，只需提供 Redis/MySQL 实现。不在本次加固范围。 |
| 消息队列集成 | 异步任务调度（如批量 Agent 调用）属于业务层，框架提供同步/流式两种调用方式已足够。 |

---

## 七、实施顺序与里程碑

```
Phase 1 (P0)         Phase 2 (P1)          Phase 3 (P2)           Phase 4 (P3)
──────1天────────     ──────1.5天─────       ──────2天───────        ──────1天──────
│                  │                      │                      │
│ 2.1 RateLimit    │ 3.1 RequestId        │ 4.1 Hook错误传播     │ 5.1 API限流
│ 2.2 addTurn      │ 3.2 AgentMetrics     │ 4.2 并发session      │ 5.2 分页
│ 2.3 isRetryable  │ 3.3 CircuitBreaker   │ 4.3 退避策略         │ 5.3 MCP健康检查
│ 2.4 summarize    │ 3.4 TokenEstimator   │                      │ 5.4 审批清理
│ 2.5 stream超时   │ 3.5 MCP健康检查接口  │                      │
│ 2.6 COW list     │                      │                      │
│ 2.7 审批TTL      │                      │                      │
│                  │                      │                      │
└─ 改动文件: 10    └─ 改动文件: 8         └─ 改动文件: 4         └─ 改动文件: 6
   新增行: ~60        新增行: ~200           修改行: ~80            新增行: ~120
   不碰接口           新增 3 个 SPI           不改变接口              纯 bootstrap
```

**总计**：约 28 个文件变动，新增 ~380 行，修改 ~200 行。

---

## 八、风险矩阵

| 改动 | 回滚难度 | 影响面 | 需要补测 | 备注 |
|------|----------|--------|----------|------|
| RateLimitHook CAS | 低 | 仅自己 | 并发测试 | 行为语义微调 |
| addTurn 同步 | 低 | Session类 | 并发测试 | - |
| isRetryable 扩展 | 低 | 仅自己 | 单元测试 | 可能增加重试次数 |
| summarize toolChoice | 极低 | 仅自己 | 无需 | - |
| stream 超时 | 中 | 流式调用方 | 集成测试 | 可能过早超时，需调参数 |
| COW list 替换 | 低 | register+遍历 | 无需 | - |
| AgentMetrics SPI | 极低 | 无（NOOP默认） | 单元测试 | - |
| CircuitBreaker | 中 | LLM调用链 | 集成测试 | 默认关闭 |
| **Hook 错误传播** | **高** | **所有流式调用** | **大量集成测试** | **需最谨慎** |
| 并发 session | 低 | 仅 ReActAgent | 并发测试 | - |
| 退避策略 | 低 | Loop 节奏 | 无需 | 默认不启用 |

---

## 九、回滚策略

每个 Phase 完成后打 tag：

```
git tag enterprise-hardening-phase-1
git tag enterprise-hardening-phase-2
git tag enterprise-hardening-phase-3
git tag enterprise-hardening-phase-4
```

如果 Phase 4（Hook 错误传播）引入问题，可以 revert 到 Phase 2 而保留前两批的 bug 修复和 SPI 新增。

Hook 错误传播建议：先在测试环境跑 1 周全量集成测试，确认没有破坏任何现有流式行为后再合并。可以加一个开关 `errorPropagationMode`（默认 false = 旧行为，true = 新行为），允许在线上灰度切换。
