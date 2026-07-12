# 主链路流水线简化设计

## 目标

消除主链路中值注入、静态方法错位、原始 dispatch 散落等问题。让主链路流水线极简清晰，每个步骤以方法引用表达。

## Part A: 提取纯工具方法

### A1: ChatResponseUtil

`ModelCallPipeline.assembleResponseFromChunks` 是纯静态函数，不依赖任何实例状态，却被挂在 Pipeline 类上，导致 `LoopExecutor` 和 `RequestPipeline` 需要跨类静态调用。

方案：新增 `core/util/ChatResponseUtil`，搬入 `fromChunks(List<ChatStreamChunk>): ChatResponse`。

影响：
- 新增 `ChatResponseUtil.java`
- `ModelCallPipeline.assembleResponseFromChunks` 删除
- `LoopExecutor.executeReason` 改为 `ChatResponseUtil::fromChunks`
- `RequestPipeline.execute` 改为 `ChatResponseUtil::fromChunks`
- `ModelCallPipeline.execute` 改为 `ChatResponseUtil::fromChunks`

## Part B: 提取 LoopContext 装配器

### B1: LoopContextAssembler

当前 `LoopContextFactory.create` 只做部分构建，调用方还需要手动注入 intervention state。`GenerateOptions` 的 mapping 也内联在 factory 里。

方案：新增 `LoopContextAssembler`，收拢完整装配逻辑：
- `LoopContext.builder(...)` 构建
- `GenerateOptions` 从 `AgentExecutionConfig` 映射
- `InterventionState` 从 `SessionLoadResult` 注入

调用方只需一行 `LoopContextAssembler.assemble(ctx, execConfig, sessionResult)`。

### B2: EnrichedMessages → SessionLoadResult

`EnrichedMessages` 有 3 个构造器，其中之一做"合并"语义（从 A 取消息、从 B 取介入信息），这是数据流扭曲的信号。

方案：重命名为 `SessionLoadResult`，新增 `withMessages(List<Msg>)` 方法替代合并构造器：
```java
result.withMessages(enrichedMsgs)  // 替换消息，保留介入信息
```

内部类保留在 `RequestPipeline` 中。

影响：
- 新增 `LoopContextAssembler.java`
- 删除 `LoopContextFactory.java`
- `EnrichedMessages` → `SessionLoadResult`，消除 1 个构造器
- `prepareAndExecute` 缩减约 10 行

## Part C: HookPipeline 收拢剩余 dispatch

设计 doc `2026-07-11-loop-hook-simplification-design.md` Part B2 规划了 `HookPipeline` 门面收拢全部 Hook 分发。但 `summarizeThenReason` 和 `handleInterruptStream` 中的 dispatch 仍然裸露，且 `executeReason` 的后处理逻辑缺少 hook 扩展点。

### C1: POST_MODEL + TokenEstimationHook

当前 `executeReason` 在 `concatWith(Flux.defer(...))` 中做了 4 件事：组装 ChatResponse、写入 ctx、token 估算、构建 usage chunk。挤在一个 lambda 里，无法扩展。

方案：新增 `HookEventType.POST_MODEL`，由 `HookPipeline.onPostModel` 分发。
Token 计算由系统内置 `TokenEstimationHook implements Hook` 实现，
挂在 `POST_MODEL` 上。HookPipeline 不感知 TokenEstimator。

**HookPipeline.onPostModel:**
```
dispatch(POST_MODEL, ctx, response) → abort → error
→ 从 event 读取 usage chunk → 返回
```

**TokenEstimationHook (core/hook/impl/):**
```
supports() → [POST_MODEL]
onEvent() → 写入 ctx（lastResponse, tokens, message）
         → token 估算
         → 构建 usage chunk
         → 写入 event.payload(USAGE_CHUNK)
```

Hook 相关常量提取到 `CoreConstants`。

### C2: preSummarize

当前 `summarizeThenReason` 直接调用 `hookPipeline.dispatch(event, hc)` 并内联处理 abort/bypass/注入提示词/修改 GenerateOptions。

方案：新增 `HookPipeline.preSummarize(ctx)` 模板方法，内部：
```
dispatch(PRE_SUMMARIZE) → abort → error
→ bypass → 注入 bypass 消息 + 返回 bypass chunk
→ 默认 → 注入总结提示词 + 禁用工具，返回 CONTINUE
```

新增 `PreSummarizeResult` 内部类（Action: CONTINUE / RETURN_CHUNK）。

### C3: onInterrupt

当前 `handleInterruptStream` 直接调用 `hookPipeline.dispatch(event, hc)` 并内联处理 abort/feedback 注入/构建中断响应。

方案：新增 `HookPipeline.onInterrupt(ctx)` 模板方法，内部：
```
dispatch(ON_INTERRUPT) → abort → 返回中断终止 chunk
→ 有 feedback → 注入 feedback + 清除中断，返回 RECOVER
→ 默认 → 返回中断终止 chunk
```

新增 `InterruptResult` 内部类（Action: RECOVER / RETURN_CHUNK）。

影响：
- `HookEventType` 新增 `POST_MODEL`
- `HookPipeline` 新增 3 个模板方法：`onPostModel` / `preSummarize` / `onInterrupt`
- `HookPipeline` 新增 2 个结果类型：`PreSummarizeResult` / `InterruptResult`
- 新增 `TokenEstimationHook`（系统内置 hook，迁入 TokenEstimator）
- `LoopExecutor` 移除 `TokenEstimator` 直接依赖，移除 `applyResponse` / `buildUsageChunk`
- `LoopExecutor.executeReason` 从 ~30 行缩减到 ~8 行
- `LoopExecutor.summarizeThenReason` 从 ~25 行缩减到 ~6 行
- `LoopExecutor.handleInterruptStream` 从 ~25 行缩减到 ~5 行

## Part D: prepareAndExecute 拍平

```java
// Before (16行)
private Flux<ChatStreamChunk> prepareAndExecute(RuntimeContext ctx, List<Msg> messages) {
    return loadSessionAndHistory(ctx, messages)
            .flatMap(result -> injectSystemMessage(result.messages)
                    .map(enrichedMsgs -> new EnrichedMessages(enrichedMsgs, result)))
            .flatMapMany(em -> {
                LoopContext loopCtx = LoopContextFactory.create(ctx, em.messages, execConfig);
                if (em.interventionId != null) { ... }
                return Flux.using(
                        () -> trackActive(loopCtx),
                        lc -> withTimeoutAndGate(lc, loopExecutor.runStream(lc)),
                        this::untrackActive);
            });
}

// After (6行)
private Flux<ChatStreamChunk> prepareAndExecute(RuntimeContext ctx, List<Msg> messages) {
    return loadSessionAndHistory(ctx, messages)
            .flatMap(result -> injectSystemMessage(result.messages)
                    .map(result::withMessages))
            .map(result -> LoopContextAssembler.assemble(ctx, execConfig, result))
            .flatMapMany(this::executeWithTracking);
}
```

`executeWithTracking` 提取为独立 private method，封装 `Flux.using(trackActive, run, untrackActive)`。

## 总计

| 变更 | 文件 | 行数估算 |
|------|------|---------|
| 新增 `ChatResponseUtil` | `core/util/` | +50 |
| 新增 `LoopContextAssembler` | `core/agent/loop/` | +45 |
| 删除 `LoopContextFactory` | `core/agent/loop/` | -43 |
| `HookPipeline` 新增 3 个模板方法 + 2 个结果类型 | `core/hook/` | +80 |
| 新增 `TokenEstimationHook` | `core/hook/impl/` | +40 |
| `HookEventType` 新增 `POST_MODEL` | `core/hook/` | +2 |
| `ModelCallPipeline` 移除 `assembleResponseFromChunks` | `core/agent/loop/` | -44 |
| `LoopExecutor` 移除 `TokenEstimator` 依赖 + 简化 | `core/agent/loop/` | -40 |
| `RequestPipeline.prepareAndExecute` 拍平 | `core/agent/loop/` | -10 |
| `RequestPipeline` 内部类重命名 + 简化 | `core/agent/loop/` | -5 |

净增 ~75 行，消除 `LoopContextFactory` 类，新增 `TokenEstimationHook` 系统内置 hook，
主链路 4 个核心方法缩减 ~60%，`LoopExecutor` 移除 `TokenEstimator` 直接依赖。

## 编码规范

- 所有方法和属性使用 `/**` 后换行 `* desc` 换行 `*/` 格式的 Javadoc
- 字符串字面量提取到 `CoreConstants` 常量类
