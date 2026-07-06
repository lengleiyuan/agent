# Observe 阶段闭环与会话持久化重构

## 目标

让 LoopDecisionEngine 成为 ReAct 状态机的唯一路由裁判，将 `dispatchAfterIteration`（会话持久化）收敛到 Observe 阶段一处触发。

## 现状分析

### DecisionEngine 只在一处被调用

`LoopExecutor.runStream()` 第 100 行是**唯一**调用点：

```java
Decision d = engine.evaluate(Phase.guard(), ctx);
```

REASON、ACT、OBSERVE 三个阶段的评估逻辑全是死代码 — LoopExecutor 自己做了所有路由：

| 阶段 | `evaluate()` 返回值 | 实际被调用？ | 真实路由谁做的 |
|------|--------------------|-------------|---------------|
| GUARD | `Continue(Reason)` | 是（`runStream`） | — |
| REASON | `Continue(current)` — "调用方自己决定" | **否** | `executeReason` 自己判断有无工具，直接跳到 Act 或 STOP |
| ACT | `Continue(Observe)` | **否** | `executeAct` 直接 `dispatchAfterIteration` + `runStream` 回到 Guard |
| OBSERVE | `iteration++` + `Continue(Guard)` | **否** | 从未进入！`iteration++` 实际写在 `executeAct:345` |

### `dispatchAfterIteration` 散落在 4 处

```
executeReason（无工具）→ dispatchAfterIteration → STOP
executeAct              → dispatchAfterIteration → runStream
executeAndContinue(Obs) → dispatchAfterIteration → runStream
resumeToolWithArgs      → dispatchAfterIteration → runStream
```

`SessionPersistenceHook` 监听 `AFTER_ITERATION` 事件，但触发点分散在 4 处，没有统一的生命周期保证。

### Decision.stop() 永远不会被返回

`evaluate()` 内部永远不会返回 `Decision.stop()`。`runStream` 检查了 `d.isStop()` 但结果始终为 false。静态工厂 `buildInterruptedResponse` 存在但走的是另一条路径。

## 目标架构

### 状态机成为唯一路由源

```
executePhase(decision, ctx):
  d.isStop()       → Flux.empty()
  d.next == REASON → executeReason → engine.evaluate(REASON) → executePhase(next)
  d.next == ACT    → executeAct → engine.evaluate(ACT) → executePhase(next)
  d.next == OBSERVE → executeObserve → engine.evaluate(OBSERVE) → executePhase(next)
```

每个阶段执行完毕 → 回访引擎获取决策 → 递归调用 executePhase。引擎是唯一路由裁判，无旁路。

### 各场景完整流转

**无工具调用：**
```
GUARD→REASON(模型)→REASON(引擎:无工具→OBSERVE)→OBSERVE(持久化)→OBSERVE(引擎→GUARD)→GUARD(引擎:complete→STOP)
```

**有工具调用：**
```
GUARD→REASON(模型)→REASON(引擎:有工具→ACT)→ACT(执行工具)→ACT(引擎→OBSERVE)→OBSERVE(持久化)→OBSERVE(引擎→GUARD)→GUARD(引擎→REASON)→...
```

**介入恢复：**
```
resumeToolWithArgs → executePhase(OBSERVE) → OBSERVE(持久化) → GUARD → REASON → ...
```

**达到最大迭代：**
```
GUARD(注入总结prompt)→REASON(携带总结的模型)→REASON(引擎:无工具→OBSERVE)→OBSERVE→GUARD(complete→STOP)
```

### `dispatchAfterIteration` 收敛至 1 处

改动后：仅 `executeObserve` 调用 `dispatchAfterIteration`。每次迭代 — 无论路径 — 恰好通过 Observe 一次。

### Decision.stop() 是闭环出口

`Guard` 检查 `ctx.isComplete()` 返回 `Stop`，`executePhase` 收到 Stop 后结束递归。引擎拥有说"结束"的权力，终止逻辑不泄漏到执行器。

## 代码改动清单

### 1. LoopContext — 新增 complete 标记

```java
/** 会话是否已完成（无需继续推理） */
private volatile boolean complete;

/** 标记会话完成，下一轮 Guard 评估时将返回 Stop */
public void markComplete() { this.complete = true; }

/** @return 会话是否已完成 */
public boolean isComplete() { return complete; }
```

### 2. LoopDecisionEngine — 复活全部阶段评估

**`evaluate(Guard, ctx)`：** 新增 `isComplete()` 检查，在最大迭代检查之前。完成时返回 `Stop`。

**`evaluate(Reason, ctx)`：** 读 `ctx.getLastResponse()` 的 `ToolUseBlock` 列表。有工具 → `Continue(Act(tools))`。无工具 → `markComplete()` → `Continue(Observe)`。

**`evaluate(Act, ctx)`：** 返回 `Continue(Observe)`。

**`evaluate(Observe, ctx)`：** 返回 `Continue(Guard)`。`iteration++` 移出，由 `executeObserve` 负责。

### 3. LoopExecutor — 核心重构

**`executePhase(decision, ctx)`（新增）：** 路由中枢。通过 `concatWith(Flux.defer(...))` 递归 — 与当前 `runStream` 递归机制相同，订阅级递归无栈溢出风险。

**`executeReason`：** 仅调模型、收集回复。设置 `lastResponse`、累加 token、添加 assistant 消息到 ctx、发射模型 chunk。不检查工具调用、不决定下一阶段。

**`executeAct`：** 记录指标（使用 `ctx.getIteration() + 1` 因为 iteration 尚未递增）。执行工具。追加工具结果到 ctx。应用 backoff。不递增 iteration、不分发 after-iteration hook。

**`executeObserve`（替代当前 Observe 分支）：** 递增 iteration → `dispatchAfterIteration`（触发 `AFTER_ITERATION` → `SessionPersistenceHook`）→ 返回 `Mono<Void>`。

**`resumeToolWithArgs`：** 将手动的 `dispatchAfterIteration` + `runStream` 替换为 `executePhase(Decision.continue_(Phase.observe()), ctx)`。

**`dispatchSummarizeHook`：** 将 `executeAndContinue(Phase.reason())` 替换为 `executePhase(Decision.continue_(Phase.reason()), ctx)`。

**删除：** `executeAndContinue` 方法，完全由 `executePhase` 替代。

## 坑点与解决方案

### 1. Assistant 消息重复追加（关键）

**问题：** 有工具时，含 `tool_use` blocks 的 assistant 消息必须在工具结果之前添加。旧代码在 `appendToolResults` 中追加 assistant 消息。新代码在 `executeReason` 中追加。不能重复。

**解决：** `executeReason` 负责添加 assistant 消息（含 tool_use blocks）。`appendToolResults` 只追加 tool result 消息，不再追加 assistant 消息。

### 2. metrics 的 iteration 值

**问题：** `iteration++` 从 `executeAct` 移到 `executeObserve`。`executeAct` 记录指标时 iteration 尚未递增。

**解决：** `metrics.recordIteration(..., ctx.getIteration() + 1, toolCount)`。

### 3. 递归深度（无风险）

`executePhase` 递归在 `concatWith(Flux.defer(...))` 内发生，是订阅级递归而非调用栈递归。与当前 `runStream` 模式完全相同。

### 4. 空模型回复（无风险）

`assembleResponseFromChunks` 返回 null → `return Flux.empty()`。与当前行为一致，无需调引擎。

### 5. summarize hook 路由

`dispatchSummarizeHook` → `executePhase(Reason)` → 引擎评估（无工具，因为 `ToolChoicePolicy.NONE` 已设置）→ `markComplete` → `Observe` → `Guard(complete)` → `Stop`。闭环正确。

### 6. 介入恢复路径

`resumeToolWithArgs` → `executePhase(Observe)` → 持久化 → `Guard` → `Reason`。介入结果已在 ctx 消息中，循环正常恢复。

## 涉及文件

| 文件 | 改动 |
|------|------|
| `LoopContext.java` | 新增 `complete` 字段 + `markComplete()`/`isComplete()` |
| `LoopDecisionEngine.java` | 新增 `evaluate(Reason)` 读工具调用；`evaluate(Guard)` 检查 `isComplete`；`iteration++` 从 `evaluateObserve` 移除 |
| `LoopExecutor.java` | 新增 `executePhase`；`executeReason`/`executeAct` 剥离路由逻辑；新增 `executeObserve`；删除 `executeAndContinue` |
| `LoopDecisionEngineTest.java` | 新增 Reason 评估（有工具/无工具）、Guard 返回 Stop、Complete 标记测试 |
| `LoopExecutorTest.java` | 更新验证所有场景走 Observe 路径 |
| `LoopExecutorInterventionTest.java` | 验证介入恢复走 Observe 路径 |

## 验证

```bash
mvn test -pl agent-core
```

所有现有测试通过 + 新增测试覆盖 Observe 闭环路径。
