# 会话级串行化门控 (Session Gate) 方案

日期: 2026-07-04
状态: 待确认

## 目标

同一 session 的请求 FIFO 排队执行（后一个等前一个完成），不同 session 的请求不受影响，并发执行。

## 设计

### 核心机制：`Sinks.One<Void>` 链

```
SessionGate
  └── ConcurrentHashMap<String, Sinks.One<Void>>  // sessionId → 当前请求的完成信号

请求 A（session=abc）：
  gate.put("abc", gateA) → null（无前序）
  → 立即执行 → doFinally: gateA.tryEmitEmpty()

请求 B（session=abc，A 执行中到达）：
  gate.put("abc", gateB) → gateA（前序）
  → gateA.asMono()...then(执行B) → doFinally: gateB.tryEmitEmpty()

请求 C（session=xyz，不同 session）：
  gate.put("xyz", gateC) → null（独立 gate）
  → 立即执行（与 abc 并发）
```

`Sinks.One.asMono()` 会缓存信号——即使前序请求在 B 订阅前就完成了，B 也会立即收到完成信号并开始执行。无需轮询、无需锁。

### SessionGate.java

```java
package cd.lan1akea.core.agent.loop;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级串行化门控。
 * 同一 session 的请求 FIFO 排队，不同 session 并发。
 * 基于 ConcurrentHashMap.put（原子替换）+ Sinks.One（异步信号）。
 */
public class SessionGate {

    private final ConcurrentHashMap<String, Sinks.One<Void>> gates = new ConcurrentHashMap<>();

    /**
     * 串行化 Mono 执行。sessionId 为 null 时不排队。
     */
    public <T> Mono<T> enqueue(String sessionId, Mono<T> work) {
        if (sessionId == null) return work;
        return Mono.defer(() -> {
            Sinks.One<Void> myGate = Sinks.one();
            Sinks.One<Void> prevGate = gates.put(sessionId, myGate);
            Mono<T> execution = prevGate != null
                    ? prevGate.asMono().then(work)
                    : Mono.defer(() -> work);
            return execution.doFinally(s -> myGate.tryEmitEmpty());
        });
    }

    /**
     * 串行化 Flux 执行。等待前序完成 → 执行 → 信号完成。
     */
    public Flux<ChatStreamChunk> enqueueStream(String sessionId, Flux<ChatStreamChunk> work) {
        if (sessionId == null) return work;
        return Flux.defer(() -> {
            Sinks.One<Void> myGate = Sinks.one();
            Sinks.One<Void> prevGate = gates.put(sessionId, myGate);
            Flux<ChatStreamChunk> execution = prevGate != null
                    ? prevGate.asMono().thenMany(work)
                    : Flux.defer(() -> work);
            return execution.doFinally(s -> myGate.tryEmitEmpty());
        });
    }
}
```

### 集成点：RequestPipeline

在 `executeStream()` 和 `execute()` 中，LoopContext 创建后、loopExecutor 调用前，用 gate 包裹：

```java
// executeStream() 中的 concatMap lambda:
LoopContext loopCtx = LoopContextFactory.create(...);
activeRequests.put(loopCtx.getRequestId(), loopCtx);
Flux<ChatStreamChunk> stream = loopExecutor.runStream(loopCtx)
        .doFinally(s -> activeRequests.remove(loopCtx.getRequestId()));
// ← 包一层 gate
stream = sessionGate.enqueueStream(loopCtx.getSessionId(), stream);
```

### 关键语义

| 场景 | 行为 |
|------|------|
| sessionId = null | 不排队，直接执行（兼容无会话场景） |
| 同 session 并发到达 | `put` 决定顺序，先成功的先执行，后来的排队 |
| 前序正常完成 | 后序收到信号，立即开始 |
| 前序异常/超时 | `doFinally` 确保信号发出，后序不受阻 |
| 前序被 cancel | `doFinally` 触发，后序继续 |
| 进程崩溃 | gate 丢失，无影响（下次会话重建） |
| 长时间空闲 session | `Sinks.One` 占少量内存，可接受 |
| 中断（interrupt） | 中断的是当前执行，queued 请求收到的 gate 信号正常 |

### 文件变更

| 操作 | 文件 |
|------|------|
| 新增 | `agent/loop/SessionGate.java` (~50行) |
| 修改 | `agent/loop/RequestPipeline.java` (+8行，两处 gate 包裹) |

### 风险

| 风险 | 评估 |
|------|------|
| 死锁 | 无——信号机制，不会互锁 |
| 内存泄漏 | 低——Sinks.One 对象轻量，session 长期不用也不累积 |
| 性能 | 极低——一次 ConcurrentHashMap.put + Sinks 创建 |
| 测试 | 需要验证 FIFO 顺序和异常恢复，建议并发测试 |
