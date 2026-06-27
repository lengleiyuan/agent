# HarnessAgent Hook 线程安全编写指南

## 前提：理解执行模型

```
每次 chat() 调用:
  ├── PRE_CALL      event (NEW)
  ├── PRE_REASONING  event (NEW，同一个 event 贯穿 PRE → AroundHook → POST)
  │   ├── [AroundHook.aroundReasoning: 前置]
  │   ├── LLM 调用
  │   └── [AroundHook.aroundReasoning: 后置]  ← 同一方法作用域
  ├── POST_REASONING 同一个 event ← payload 可跨 PRE/POST 共享
  ├── PRE_ACTING     event (NEW)
  │   ├── PRE_TOOL_CALL  event (NEW，同一个 event 贯穿 PRE → AroundHook → POST)
  │   │   ├── [AroundHook.aroundToolCall: 前置]
  │   │   ├── 工具执行
  │   │   └── [AroundHook.aroundToolCall: 后置]
  │   └── POST_TOOL_CALL 同一个 event
  ├── POST_ACTING    同一个 event
  └── POST_CALL      event (NEW)
```

**关键事实**：
1. PRE 和 POST 现在是**同一个 event 对象**——payload 可以从 PRE 自然传递到 POST
2. `AroundHook` 的前置和后置逻辑在**同一个方法作用域**内，局部变量天然线程安全
3. 链式 `Hook` 和包裹式 `AroundHook` 共存，互不冲突

---

## 最佳实践：选择 Hook 还是 AroundHook

| 场景 | 推荐 | 原因 |
|------|------|------|
| 内容过滤、敏感词检查 | `Hook` (POST_REASONING) | 只需检查输出，无状态 |
| 工具权限校验 | `Hook` (PRE_TOOL_CALL) | 只需判断 allow/deny |
| 审计日志 | `Hook` | 只记录，不需跨阶段状态 |
| **计时统计** | **`AroundHook`** | PRE 记开始 + POST 算耗时，需跨阶段传递 |
| **Token 追踪** | **`AroundHook`** | PRE 记录消耗 + POST 汇总 |
| **事务管理** | **`AroundHook`** | 打开/提交/回滚在同一个方法作用域 |
| **分布式追踪 span** | **`AroundHook`** | 创建 span → 执行 → 关闭 span |

**原则**：如果你的逻辑需要 "记下 PRE 的什么，然后在 POST 用"，就用 `AroundHook`。

---

## 规则 0：首选 AroundHook（新写法，零心智负担）

```java
public class TimingHook implements AroundHook {

    @Override
    public Mono<HookEvent> aroundReasoning(HookEvent event, HookContext ctx,
                                            Function<HookEvent, Mono<HookEvent>> next) {
        long start = System.nanoTime();          // ← 局部变量，线程私有。零同步。
        return next.apply(event)                  // ← 调用下游（LLM）
            .doOnSuccess(e -> {
                long elapsed = System.nanoTime() - start;
                System.out.println("推理耗时: " + elapsed / 1_000_000 + "ms, " +
                    "session=" + ctx.getSessionId());
            });
    }
}
```

对比链式 Hook 需要的代码量：
- **链式**：2 个 Hook 类 + ConcurrentHashMap/Reactor Context 桥接状态
- **AroundHook**：1 个方法，1 个 `long` 局部变量，6 行代码

---

## 规则 1：Hook 必须是无状态的，或只用并发安全容器存全局统计

### ✅ 正确：无状态 —— 所有数据来自参数

```java
public class SafeContentFilter implements Hook {

    private final List<String> blockedWords;  // ← final，构造后不可变

    public SafeContentFilter(List<String> blockedWords) {
        this.blockedWords = List.copyOf(blockedWords);
    }

    @Override
    public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
        // 从 event 和 context 参数获取一切需要的数据
        String tenantId = context.getTenantId();       // ← 参数
        String userId = context.getUserId();           // ← 参数

        if (event instanceof ReasoningEvent re) {
            List<Msg> messages = re.getMessages();
            // 纯函数：输入 → 检查 → 结果
            for (Msg msg : messages) {
                for (String blocked : blockedWords) {
                    if (msg.getTextContent().contains(blocked)) {
                        return Mono.just(HookResult.abort("敏感词: " + blocked));
                    }
                }
            }
        }
        return Mono.just(HookResult.continue_());
    }

    @Override public String getName() { return "content-filter"; }
    @Override public Set<HookEventType> getSubscribedEventTypes() {
        return Set.of(HookEventType.POST_REASONING);
    }
}
```

### ✅ 正确：全局计数器用 Atomic*

```java
public class CallCounterHook implements Hook {
    // ← 全局统计，多线程安全
    private final AtomicLong totalCalls = new AtomicLong(0);
    private final AtomicLong totalTokens = new AtomicLong(0);

    @Override
    public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
        totalCalls.incrementAndGet();
        // AtomicLong 保证原子递增，无需加锁
        return Mono.just(HookResult.continue_());
    }

    public long getTotalCalls() { return totalCalls.get(); }
    public long getTotalTokens() { return totalTokens.get(); }
    // ...
}
```

### ✅ 正确：per-租户 统计用 ConcurrentHashMap

```java
public class PerTenantCounterHook implements Hook {
    // ← key 是全局维度（tenant），不是 per-request
    private final ConcurrentHashMap<String, AtomicLong> countsPerTenant
        = new ConcurrentHashMap<>();

    @Override
    public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
        String tenant = context.getTenantId();
        countsPerTenant
            .computeIfAbsent(tenant, k -> new AtomicLong())
            .incrementAndGet();
        return Mono.just(HookResult.continue_());
    }
}
```

### ❌ 错误：在实例字段上存 per-request 数据

```java
public class UnsafeHook implements Hook {
    private String currentUserId;    // ❌ per-request 状态存在共享实例上！
    private long currentStartTime;   // ❌ 线程B 会覆盖线程A 写入的值

    @Override
    public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
        this.currentUserId = context.getUserId();  // ❌ 竞态！
        this.currentStartTime = System.nanoTime(); // ❌ 竞态！
        // ...
        return Mono.just(HookResult.continue_());
    }
}
```

---

## 规则 2：不要尝试用 HookEvent payload 跨 PRE/POST 传递数据

这是最常见的陷阱。你可能会想：

```java
// ❌ 这是行不通的！
public class PreHook implements Hook {
    public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
        event.setPayload("start_time", System.nanoTime());  // 写入 pre event
        return Mono.just(HookResult.continue_());
    }
}

public class PostHook implements Hook {
    public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
        Long start = event.getPayload("start_time");  // ← null!
        // pre event 和 post event 是不同的对象，payload 不会自动传递
    }
}
```

**原因**：`ReActLoop` 中 PRE 和 POST 各 new 了一个 event 对象，它们之间没有数据继承关系。

---

## 规则 3：需要跨 PRE/POST 传递数据？用 Reactor Context 或外部存储

### 方案 A：Reactor Context（推荐，但需框架配合）

如果你需要 PRE_REASONING 记录开始时间，POST_REASONING 计算耗时：

```java
public class TimingHook implements Hook {
    static final String START_KEY = "timing_start_ns";

    @Override
    public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
        if (event.getHookEventType() == HookEventType.PRE_REASONING) {
            // 写入 Reactor Context —— 沿 Mono 链传播
            return Mono.just(HookResult.continue_())
                .contextWrite(ctx -> ctx.put(START_KEY, System.nanoTime()));
        }

        // POST_REASONING —— 从 Reactor Context 读取
        return Mono.deferContextual(ctxView -> {
            if (ctxView.hasKey(START_KEY)) {
                long elapsed = System.nanoTime() - ctxView.<Long>get(START_KEY);
                log.info("推理耗时: {}ms", elapsed / 1_000_000);
            }
            return Mono.just(HookResult.continue_());
        });
    }

    @Override public String getName() { return "timing"; }
    @Override public Set<HookEventType> getSubscribedEventTypes() {
        return Set.of(HookEventType.PRE_REASONING, HookEventType.POST_REASONING);
    }
}
```

**但这依赖 ReActLoop 中 PRE 和 POST 在同一个 Mono 链中**——看 `reasoningStep()`:
```java
hookDispatcher.dispatch(PRE_REASONING, pre, hc)   // contextWrite 发生在这一步
    .flatMap(r -> model.chatWithTools(...))        // context 沿 flatMap 传播 ✅
    .flatMap(resp -> hookDispatcher.dispatch(POST_REASONING, post, hc))
```

确实在同一个链中。**Reactor Context 方案是可行的。**

### 方案 B：外部存储

如果 ReActor Context 不满足需求（比如需要跨多个迭代聚合数据）：

```java
public class SessionMetricsHook implements Hook {
    private final AgentStateStore stateStore;  // ← 构造注入

    @Override
    public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
        String sessionId = context.getSessionId();

        if (event.getHookEventType() == HookEventType.PRE_REASONING) {
            // 把 startTime 持久化到 session 的扩展属性中
            return stateStore.findById(new SessionId(sessionId))
                .flatMap(session -> {
                    session.setAttribute("last_reasoning_start", System.nanoTime());
                    return stateStore.save(session);
                })
                .thenReturn(HookResult.continue_());
        }

        // POST_REASONING —— 读取
        return stateStore.findById(new SessionId(sessionId))
            .flatMap(session -> {
                Long start = session.getAttribute("last_reasoning_start");
                if (start != null) {
                    long elapsed = System.nanoTime() - start;
                    log.info("session={} 推理耗时={}ms", sessionId, elapsed / 1_000_000);
                }
                return Mono.just(HookResult.continue_());
            });
    }
}
```

---

## 规则 4：用 `@ToolFunction` 注解的工具也遵循同样规则

```java
// ✅ 正确：无状态工具
public class WebSearchTool implements ToolSupport {
    @ToolFunction(name = "web_search", description = "搜索网页")
    public ToolResult search(@ToolParam("query") String query) {
        // 局部变量，每次调用独立
        String result = httpClient.get("https://search?q=" + query);
        return ToolResult.success(result);
    }
}

// ❌ 错误：有状态工具
public class CounterTool implements ToolSupport {
    private int count = 0;  // ❌ 并发调用会混乱

    @ToolFunction(name = "counter")
    public ToolResult count() {
        return ToolResult.success(String.valueOf(++count));  // ❌ 非原子操作
    }
}

// ✅ 修正：用 AtomicInteger
public class CounterTool implements ToolSupport {
    private final AtomicInteger count = new AtomicInteger(0);  // ✅

    @ToolFunction(name = "counter")
    public ToolResult count() {
        return ToolResult.success(String.valueOf(count.incrementAndGet()));  // ✅
    }
}
```

---

## 检查清单

写一个新的 Hook 时，逐条核对：

- [ ] Hook 的构造参数是否都是 final？（构造后不可变）
- [ ] 是否有 per-request 可变字段？（应该没有——数据从 `onEvent` 参数来）
- [ ] 全局计数器用了 `Atomic*` 吗？（`int` → `AtomicInteger`）
- [ ] per-租户/用户统计用了 `ConcurrentHashMap` 吗？（`HashMap` → `ConcurrentHashMap`）
- [ ] 是否尝试通过 `event.setPayload()` 向另一个 Hook 传递数据？（不会生效，PRE/POST 是不同 event 对象）
- [ ] 需要跨 PRE/POST 传递状态时，用了 Reactor Context 还是外部存储？
- [ ] 工具实现中有 `static` 可变字段吗？（不应该有，除非是 `static final` 不可变常量）
- [ ] 工具执行中有修改共享集合的操作吗？（保证每次 `execute()` 是纯函数）
