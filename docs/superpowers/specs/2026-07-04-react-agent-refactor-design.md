# ReActAgent 主链路重构设计

日期: 2026-07-04
状态: 已确认

## 概述

对 ReActAgent 和 ReActLoop 进行结构性重构，核心思路：**以流式路径为 canonical 实现，非流式由此派生**，
从根本上消除 Mono/Flux 双轨重复。

## 当前问题

| 问题 | 严重程度 | 行数浪费 |
|------|---------|---------|
| Mono/Flux 双轨爆炸（5对方法） | 高 | ~130行 |
| doChat/doStream 管线重复 | 中 | ~60行 |
| executeSingleTool 72行巨石方法 | 中 | 难以测试 |
| reasoning 方法混5个关注点 | 中 | ~94行 |
| summarize 双轨重复 | 低 | ~40行 |
| Builder 样板代码 | 低 | 185行 |

## 重构后架构

```
ReActAgent (薄门面, ~120行)
  ├── 生命周期: build / interrupt / shutdown
  ├── 公开 API: chat / stream → 委托给 RequestPipeline
  └── 配置持有: AgentConfig

RequestPipeline (新增, ~80行)
  ├── resolveContext → loadSession → injectSystemMessage
  ├── 构建 LoopContext (委托给 LoopContextFactory)
  └── 委托给 LoopExecutor.run / runStream

LoopExecutor (重构自 ReActLoop, ~80行)
  ├── runStream() — canonical 循环实现
  ├── run() — 1行派生: runStream().collectList().map(assembleResponse)
  └── executePhase() — switch dispatch 替代递归 flatMap

LoopDecisionEngine (新增, ~70行)
  ├── evaluate(Phase, LoopContext) → Decision
  ├── 纯逻辑，无 Reactor 依赖，可同步单测
  └── 4 个 evaluateXxx 方法

ModelCallPipeline (新增, ~60行)
  ├── executeStream() — 流式 Hook + 模型调用管线
  └── execute() — 派生

ToolCallOrchestrator (新增, ~80行)
  ├── execute(tc, ctx) — 4步链式编排
  └── 审批流独立: handleSuspension()
```

## 核心设计决策

### 1. 流式为 canonical，非流式派生

```
executeStream() — 唯一循环实现
execute()       — executeStream().collectList().map(assembleResponse)
```

消灭的方法: execute(), reasoning(), acting(), summarize(), handleExternalInterrupt() — 全部由流式版本兜底。

### 2. 显式状态机替代递归

Phase 定义（普通类 + 枚举 + 静态工厂，不使用 record/sealed）:

```java
public final class Phase {
    public enum Type { GUARD, REASON, ACT, OBSERVE }

    private final Type type;
    private final List<ToolUseBlock> toolCalls;  // ACT 时有效
    private final List<ToolResult> results;       // OBSERVE 时有效

    private Phase(Type type, List<ToolUseBlock> toolCalls, List<ToolResult> results) {
        this.type = type;
        this.toolCalls = toolCalls;
        this.results = results;
    }

    public static Phase guard()                        { return new Phase(Type.GUARD, null, null); }
    public static Phase reason()                       { return new Phase(Type.REASON, null, null); }
    public static Phase act(List<ToolUseBlock> calls)  { return new Phase(Type.ACT, calls, null); }
    public static Phase observe(List<ToolResult> res)  { return new Phase(Type.OBSERVE, null, res); }

    public Type type()                        { return type; }
    public List<ToolUseBlock> toolCalls()     { return toolCalls; }
    public List<ToolResult> results()         { return results; }
    public boolean isGuard()                  { return type == Type.GUARD; }
    public boolean isReason()                 { return type == Type.REASON; }
    public boolean isAct()                    { return type == Type.ACT; }
    public boolean isObserve()                { return type == Type.OBSERVE; }
}
```

Decision 定义（普通类 + 静态工厂，不使用 record/sealed）:

```java
public final class Decision {
    private final boolean stop;
    private final Phase nextPhase;        // stop=false 时有效
    private final ChatResponse response;  // stop=true 时有效

    private Decision(boolean stop, Phase nextPhase, ChatResponse response) {
        this.stop = stop;
        this.nextPhase = nextPhase;
        this.response = response;
    }

    public static Decision continue_(Phase next)   { return new Decision(false, next, null); }
    public static Decision stop(ChatResponse resp) { return new Decision(true, null, resp); }

    public boolean isStop()              { return stop; }
    public Phase nextPhase()             { return nextPhase; }
    public ChatResponse response()       { return response; }
}
```

状态转换:

```
Guard ──[中断]──────────→ Stop
Guard ──[超迭代]────────→ Stop (summary)
Guard ──[正常]──────────→ Reason

Reason ──[无工具]───────→ Stop
Reason ──[有工具]───────→ Act

Act ────────────────────→ Observe

Observe ────────────────→ Guard (迭代+1)
```

### 3. LoopDecisionEngine 纯逻辑

不依赖: Reactor (Mono/Flux), ChatModel, ToolExecutor, HookDispatcher

`evaluate()` 是同步方法，可脱离 Reactor 环境进行单元测试。

执行流程（executor 中，使用 if/else 替代 switch pattern matching）:

```java
Flux<ChatStreamChunk> runStream(LoopContext ctx) {
    return Flux.defer(() -> {
        Decision d = engine.evaluate(Phase.guard(), ctx);
        if (d.isStop()) {
            return Flux.just(chunkFromResponse(d.response()));
        }
        return executePhase(d.nextPhase(), ctx)
                .concatWith(Flux.defer(() -> runStream(ctx)));
    });
}

private Flux<ChatStreamChunk> executePhase(Phase phase, LoopContext ctx) {
    if (phase.isReason()) {
        return modelPipeline.executeStream(ctx)
                .concatWith(Flux.defer(() -> {
                    ChatResponse resp = assembleResponse(ctx.getBufferedChunks());
                    ctx.setLastResponse(resp);
                    List<ToolUseBlock> tools = extractToolCalls(resp);
                    if (tools.isEmpty()) {
                        return dispatchAfterIteration(ctx).thenMany(Flux.empty());
                    }
                    return Flux.empty(); // 下一轮 Act 处理
                }));
    }
    if (phase.isAct()) {
        return Flux.fromIterable(phase.toolCalls())
                .flatMap(tc -> toolOrchestrator.execute(tc, ctx)
                        .map(r -> chunkFromResult(r)));
    }
    if (phase.isObserve()) {
        appendToolResults(ctx, phase.results());
        ctx.setIteration(ctx.getIteration() + 1);
        metrics.recordIteration(...);
        return dispatchAfterIteration(ctx).thenMany(Flux.empty());
    }
    return Flux.empty(); // Guard unreachable here
}
```

### 4. 组件职责边界

| 组件 | 负责 | 不负责 |
|------|------|--------|
| LoopExecutor | Reactor 编排、递归驱动 | 业务决策 |
| LoopDecisionEngine | 状态转换决策 | Reactor、模型调用 |
| ModelCallPipeline | Hook 分发 + 模型调用 | 循环控制 |
| ToolCallOrchestrator | 单工具执行编排 | 批量工具调度 |

## 关键接口

### LoopExecutor

```java
class LoopExecutor {
    Flux<ChatStreamChunk> runStream(LoopContext ctx);
    Mono<ChatResponse> run(LoopContext ctx);  // 派生

    void interrupt(LoopContext ctx);
    void shutdown();
}
```

### LoopDecisionEngine

```java
class LoopDecisionEngine {
    Decision evaluate(Phase current, LoopContext ctx);
}
```

### ModelCallPipeline

```java
class ModelCallPipeline {
    Flux<ChatStreamChunk> executeStream(LoopContext ctx);
    Mono<ChatResponse> execute(LoopContext ctx);  // 派生
}
```

### ToolCallOrchestrator

```java
class ToolCallOrchestrator {
    Mono<ToolResult> execute(ToolUseBlock tc, LoopContext ctx);
}
```

### RequestPipeline

```java
class RequestPipeline {
    Flux<ChatStreamChunk> executeStream(List<Msg> messages, RuntimeContext ctx);
    Mono<ChatResponse> execute(List<Msg> messages, RuntimeContext ctx);  // 派生
}
```

## 文件变更清单

### 新增文件 (~5个)

| 文件 | 预计行数 | 说明 |
|------|---------|------|
| `agent/loop/LoopExecutor.java` | ~80 | 循环执行器，替代 ReActLoop |
| `agent/loop/LoopDecisionEngine.java` | ~70 | 状态机决策引擎 |
| `agent/loop/ModelCallPipeline.java` | ~60 | reasoning 管线 |
| `agent/loop/ToolCallOrchestrator.java` | ~80 | 工具调用编排 |
| `agent/loop/LoopContextFactory.java` | ~15 | 统一构建 LoopContext |

### 修改文件 (~3个)

| 文件 | 变更 | 预计行数变化 |
|------|------|-------------|
| `agent/ReActAgent.java` | 删除 Builder, doChat/doStream 委托给 RequestPipeline | 721 → ~120 |
| `agent/loop/LoopContext.java` | 可能需要增加 buffered chunks 字段 | +5 |
| `agent/config/AgentConfig.java` | 确保 Builder 保留供工厂使用 | 不变 |

### 删除文件 (~1个)

| 文件 | 说明 |
|------|------|
| `agent/loop/ReActLoop.java` | 拆分为 LoopExecutor + LoopDecisionEngine + 内部组件 |

### 测试文件调整

| 文件 | 变更 |
|------|------|
| `ReActAgentTest.java` | 更新构造函数调用 |
| `ReActLoopTest.java` | 拆分到 LoopExecutorTest + LoopDecisionEngineTest |
| `ReActAgentConcurrentTest.java` | 保持，验证并发安全性 |
| `ReActLoopStreamHookTest.java` | 迁移到 ModelCallPipelineTest |
| `ReActLoopBackoffTest.java` | 迁移到 LoopExecutorTest |

## 实施顺序

1. **LoopContextFactory** — 纯新增，无依赖，可立即合并
2. **LoopDecisionEngine** — 纯逻辑，无 Reactor 依赖，独立测试
3. **ToolCallOrchestrator** — 从 executeSingleTool 提取，独立测试
4. **ModelCallPipeline** — 从 reasoning 提取，独立测试
5. **LoopExecutor** — 组合 2+3+4，替代 ReActLoop
6. **RequestPipeline** — 组合 1+5，替代 doChat/doStream
7. **ReActAgent 瘦身** — 删除 Builder，接入 RequestPipeline
8. **删除 ReActLoop** — 清理旧代码

每步可独立编译、独立测试、独立合并。
