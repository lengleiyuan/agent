# 全链路代码审查报告

> 日期：2026-07-03  
> 范围：ReActAgent → ReActLoop → ToolExecutor → Hook → Approval 完整执行链路  
> 状态：待修复

---

## 严重（不改会触发线上故障）

### 1. ToolRegistry.groups — LinkedHashMap 无线程安全

**文件**：`agent-core/.../tool/ToolRegistry.java:46`

```java
private final Map<String, ToolGroup> groups = new LinkedHashMap<>();
```

`registerGroup()` 写 + `getGroups()`/`getGroup()` 读，无同步。启动时初始化安全，运行时动态注册工具组会 `ConcurrentModificationException`。

**影响**：多租户下动态注册工具组（TENANT/USER/SESSION 作用域）时概率性崩溃。

**修复**：

```java
private final Map<String, ToolGroup> groups = Collections.synchronizedMap(new LinkedHashMap<>());
```

### 2. ResilienceChatModel.chat()/stream() 绕过熔断器

**文件**：`agent-core/.../model/ResilienceChatModel.java:99-106`

```java
@Override
public Mono<ChatResponse> chat(List<Msg> messages, GenerateOptions options) {
    return delegate.chat(messages, options);  // ❌ 没走熔断
}

@Override
public Flux<ChatStreamChunk> stream(List<Msg> messages, GenerateOptions options) {
    return delegate.stream(messages, options); // ❌ 没走熔断
}
```

只有 `chatWithTools` / `streamWithTools` 走了 `resolveState()` + 熔断逻辑。当前主链路（`ReActLoop.reasoning()` → `model.chatWithTools()`）确实命中，但接口契约上存在缺口 — 任何人直接调 `chat()` 就可以绕过熔断器。

**影响**：如果业务方用 `model.chat()` 而不是 `model.chatWithTools()`，熔断器形同虚设。

**修复**：

```java
@Override
public Mono<ChatResponse> chat(List<Msg> messages, GenerateOptions options) {
    return chatWithTools(messages, Collections.emptyList(), options);
}
```

---

## 中等

### 3. 流式/非流式迭代计数时机不一致

**文件**：`agent-core/.../loop/ReActLoop.java`

```java
// 非流式 execute()：工具执行完成后 +1
acting(ctx, toolCalls)
  .flatMap(results -> {
      appendToolResults(ctx, results);
      ctx.setIteration(ctx.getIteration() + 1);  // ← 完成后
      ...
  })

// 流式 executeStream()：工具执行开始前 +1
ctx.setIteration(ctx.getIteration() + 1);  // ← 提前了，在 actingStream 之前
List<ToolResult> toolResults = new CopyOnWriteArrayList<>();
return actingStream(ctx, toolCalls, toolResults)
```

**影响**：`maxIterations=5` 时，流式路径第 4 轮就触发 `summarize()`，而非流式到第 5 轮。同一配置下流式路径少执行一轮工具调用。

**修复**：流式路径也移到 `actingStream` 完成后 +1。

### 4. InMemoryApprovalStore — 已处理审批永不清理

**文件**：`agent-core/.../approval/InMemoryApprovalStore.java`

```java
// store 中的 APPROVED/DENIED 条目永远留在内存里
// cleanupExpired() 只处理 PENDING 过期项
```

`consume()` 也只清 `approvedKeys` 的 key，对应的 `PendingApproval` 仍在 `store` 中。

**影响**：长时间运行后内存持续增长，每个审批（无论批准/拒绝）都永久占用内存。

**修复**：

```java
@Override
public void cleanupExpired() {
    long cutoff = System.currentTimeMillis() - 600_000; // 10 分钟前
    store.values().removeIf(pa ->
        pa.getStatus() != Status.PENDING || pa.getCreatedAt() < cutoff);
}
```

### 5. ToolExecutor — sessionId 为 null 时 consume NPE

**文件**：`agent-core/.../tool/ToolExecutor.java:132`

```java
approvalStore.consume(callParam.getSessionId(), callParam.getToolName());
```

`callParam.getSessionId()` 可能为 null → `InMemoryApprovalStore.approvedKey(null, "tool")` → `"null:tool"` 字符串，虽然不会 NPE 但语义错误。其他 `ApprovalStore` 实现可能直接 `ConcurrentHashMap.get(null)` → NPE。

**修复**：`if (callParam.getSessionId() != null)` 守卫。

---

## 低

### 6. getCircuitState() 有副作用

**文件**：`agent-core/.../model/ResilienceChatModel.java:179`

```java
public CircuitState getCircuitState() {
    return resolveState();  // 内部可能 CAS 切换到 HALF_OPEN
}
```

一个 "getter" 方法会改变状态 — 对监控/健康检查调用者来说不符合预期。

**修复**：拆分为 `getCircuitState()`（只读）+ `tryTransitionToHalfOpen()`。

### 7. handleError 中 ON_ERROR dispatch 自身失败降级不优雅

**文件**：`agent-core/.../loop/ReActLoop.java`

```java
return hookDispatcher.dispatch(ev, buildHookContext(ctx))
    .onErrorResume(e -> {
        log.warning("ON_ERROR hook dispatch itself failed: " + e.getMessage());
        return Mono.just(HookResult.continue_());  // ← 降级为 continue_
    })
    .flatMap(r -> { ... })
```

`continue_()` 的结果随后会走到 `Mono.error(error)` — 等于 FATAL。降级到 `continue_()` 无法真正改变结果。应该允许在这个 fallback 中构造一个 `NEEDS_HUMAN` 的 handoff。

**修复**：`onErrorResume` 降级为 handoff 而非 FATAL。

### 8. HookChain.hooks 在并发 register 时可能 CME

**文件**：`agent-core/.../hook/HookChain.java:19`

```java
private final List<Hook> hooks;
```

在 P0 中 `AroundHookChain.hooks` 已改为 `CopyOnWriteArrayList`，但 `HookChain.hooks` 仍是普通 `ArrayList`。`register()` 调用 `hooks.sort()`，`fire()` 迭代 `hooks.get(index)` — 并发可能 `IndexOutOfBoundsException` 或排序中途读到半成品。

**修复**：`CopyOnWriteArrayList` + 注册时整体替换排序后副本。

### 9. ReActAgent 构造 → build() 两步初始化

```java
ReActAgent agent = new ReActAgent(config);
agent.build().block();  // ← 必须手动调，否则 chat() 报 "Agent 尚未构建"
```

`new ReActAgent()` 返回的对象不可用，必须再调 `build()`。编译器无法检测遗漏。

**修复**：构造函数直接设 `built=true`，或静态工厂方法替代 public 构造函数。

### 10. summarize() 跳过所有 Hook

`summarize()` 直接调 `model.chatWithTools()`，不经过 `reasoning()` → 没有 `PRE_REASONING / POST_REASONING / AFTER_ITERATION` 等 Hook。

**影响**：无法通过 Hook 在总结前压缩上下文、替换总结提示词、记录总结事件。

**修复**：`summarize()` 复用 `reasoning()` 路径（类似思路已在之前讨论过）。

---

## 不需要修（设计取舍）

以下发现经评估属于有意设计，非 bug：

| 发现 | 原因 |
|------|------|
| `executeSingleTool` 中异常消息丢失原始堆栈 | 工具异常已通过 `emitter.onError` 记录，失败结果包含异常消息即可 |
| `LoopContext` 的可变字段无 volatile | Reactor 单线程事件循环保证可见性 |
| 多个 Hook 返回 MODIFY 时只保留最后一个 | MODIFY 语义是替换事件数据，非累加 |
| `withSessionGate` 中 `doFinally` 可能不触发 | 仅当 Publisher 无订阅者时才发生，正常 path 不会 |
