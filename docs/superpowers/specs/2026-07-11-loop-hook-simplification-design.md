# Loop & Hook 简化设计

## 目标

消除主链路中过度分类造成的复杂性。减少文件数量和调用层级，让流水线一目了然。

## Part A: 消灭 Phase/Decision/LoopDecisionEngine

### 问题

当前 ReAct 循环经过三层间接：

```
LoopExecutor.runStream()
  → LoopDecisionEngine.evaluate(Phase) → Decision(Phase, stop)
  → executePhase(Decision) → switch Phase.Type { GUARD, REASON, ACT, OBSERVE }
```

`Phase` (124行) — 4个工厂方法+4个isXxx()+Type枚举，GUARD和OBSERVE几乎是空转路由。
`Decision` (60行) — (stop, nextPhase, response) 三元组，纯数据传递。
`LoopDecisionEngine` (98行) — 4个if分支路由，可内联。

### 方案

删除 Phase、Decision、LoopDecisionEngine 三个类。LoopExecutor.runStream 直接以线性顺序表达循环：

```java
public Flux<ChatStreamChunk> runStream(LoopContext ctx) {
    return Flux.defer(() -> {
        if (ctx.getInterventionState().hasPending() && !ctx.isInterrupted()) {
            return resolveIntervention(ctx);
        }
        if (ctx.isInterrupted()) {
            return handleInterrupt(ctx);
        }
        if (ctx.isComplete()) {
            return finalize(ctx);
        }
        if (ctx.getIteration() >= ctx.getMaxIterations()) {
            return summarizeThenReason(ctx);
        }
        return reason(ctx).concatWith(Flux.defer(() -> {
            List<ToolUseBlock> tools = extractToolCalls(ctx);
            if (!tools.isEmpty()) {
                return act(ctx, tools).concatWith(observe(ctx));
            }
            ctx.markComplete();
            return observe(ctx);
        }));
    });
}
```

observe 完成后通过递归 runStream 回到循环起点。

### 影响

- 删除 Phase.java, Decision.java, LoopDecisionEngine.java (-282行)
- LoopExecutor 内部方法重组，不再通过 executePhase/Decision 路由
- LoopDecisionEngine.evaluate 逻辑内联到 runStream 的 if 分支
- 相关测试：PhaseDecisionTest (74行) 删除，LoopDecisionEngineTest (131行) 用例合并到 LoopExecutorTest

## Part B: 规整 Hook 分发

### B1: 消除 HookEvent 子类

ReasoningEvent(52行)、ToolCallEvent(78行)、InterruptEvent(58行)、ErrorEvent(35行) — 这四个子类本质上是 payload key 的强类型别名。字段合并到 HookEvent，子类删除。

HookEvent 上新增强类型访问器：
- Reasoning: `getMessages()` / `setMessages()`, `getBypassMessage()` / `setBypassMessage()`
- Tool: `getTool()` / `setTool()`, `getCallParam()` / `setCallParam()`, `getResult()` / `setResult()`
- Interrupt: `getInterruptId()`, `getReason()`, `isResolved()`, `resolve()`
- Error: `getError()`, `getErrorMessage()`

Hook 实现方通过 typed getter 访问，接口不变。

### B2: HookDispatcher 添加管线模板

当前 ModelCallPipeline 和 ToolCallOrchestrator 各自手写重复的 dispatch 模式：

```java
dispatch(preEvent, hc)
    .flatMap(r -> {
        if (r.isAbort()) return error;
        if (r.isInterrupt()) return interrupt;
        return Mono.empty();
    })
    .switchIfEmpty(core.flatMap(r -> dispatch(postEvent, hc).thenReturn(r)))
```

HookDispatcher 新增统一入口：

```java
// 封装 pre hook → core → post hook 模式
public <T> Flux<T> withPrePostHooks(
        HookEventType preType, HookEventType postType,
        HookEvent preEvent, HookContext ctx,
        Function<HookEvent, Flux<T>> core);
```

ModelCallPipeline.callModelStream 和 ToolCallOrchestrator.execute 直接使用此模板。

### 影响

- 删除 ReasoningEvent.java, ToolCallEvent.java, InterruptEvent.java, ErrorEvent.java (-223行)
- HookEvent.java 新增 typed 访问器 (+30行)
- HookDispatcher 新增 withPrePostHooks 方法 (+25行)
- ModelCallPipeline.callModelStream 简化 (-20行)
- ToolCallOrchestrator.execute 简化 (-10行)

## Part C: RequestPipeline 拍平

删除 `SessionLoadResult` 和 `LoadAndMessages` 内部静态类。prepareMessages 的链式调用拍平为一个方法，直接返回 `Mono<List<Msg>>`，介入状态直接写入 LoopContext。

## 总计

| 变更 | 影响 |
|------|------|
| 删除 Phase.java | -124行 |
| 删除 Decision.java | -60行 |
| 删除 LoopDecisionEngine.java | -98行 |
| 删除 ReasoningEvent.java | -52行 |
| 删除 ToolCallEvent.java | -78行 |
| 删除 InterruptEvent.java | -58行 |
| 删除 ErrorEvent.java | -35行 |
| HookEvent 新增访问器 | +30行 |
| HookDispatcher 新增模板 | +25行 |
| ModelCallPipeline 简化 | -20行 |
| ToolCallOrchestrator 简化 | -10行 |
| RequestPipeline 拍平 | -40行 |

净减 ~520 行，消除 7 个类，主循环从 4 层调用栈变 1 层递归。
