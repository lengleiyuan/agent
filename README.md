# HarnessAgent SDK

面向业务的 AI Agent SDK。依赖 `agent-harness`，通过 Builder 一键装配。

---

## 快速开始

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("MyAgent")
    .model(new DeepSeekChatModel("sk-xxx", "deepseek-chat"))
    .build();

// 流式
agent.stream(List.of(UserMessage.of("你好")))
    .subscribe(chunk -> System.out.print(chunk.getDelta()));

// 非流式
ChatResponse resp = agent.chat(List.of(UserMessage.of("你好"))).block();
```

---

## Reactor 速查

核心接口（`Tool`、`Memory`、`AgentStateStore`）的方法返回值都是 Reactor 类型。

```java
// Mono<T> — 包含 0 或 1 个元素的异步结果
Mono.just(value);
Mono.just(ToolResult.success("内容"));
Mono.just(ToolResult.failure("原因"));
Mono.empty();
Mono.error(new RuntimeException("err"));
Mono.fromCallable(() -> blockingOperation());

// Flux<T> — 包含 0 到 N 个元素的异步流
Flux.just(a, b, c);
Flux.fromIterable(list);
Flux.empty();
```

### 阻塞转异步

```java
public class RedisMemory implements Memory {
    @Override public Mono<String> store(MemoryEntry e) {
        return Mono.fromCallable(() -> redis.set(e.getId(), e.getContent()));
    }
    @Override public Flux<MemoryEntry> retrieve(MemoryRetrievalQuery q) {
        List<String> ids = redis.search(q.getKeywords());
        return Flux.fromIterable(ids).map(id -> new MemoryEntry(id, redis.get(id)));
    }
}
```

### 禁止

```java
// ❌ 不要阻塞等待
return Mono.just(redis.blockingGet(key));
// ❌ 不要返回 null
return null;
// ✅ 正确
return Mono.fromCallable(() -> redis.blockingGet(key));
return Mono.empty();
```

---

## Builder 全部参数

```java
HarnessAgent.builder()
    .name("MyAgent")                        // 必填。Agent 唯一标识
    .model(model)                           // 必填。ChatModel 实例
    .tool(new CalculatorTool())             // 注册工具。三种形式：
                                            //   ① 实现 Tool 接口
                                            //   ② 带 @ToolFunction/@ToolParam 注解的类
                                            //   ③ .mcpServer() 自动注册的远程工具
    .hook(new AuditHook("audit"))           // 链式 Hook，按优先级排序
    .aroundHook(new TimerHook())            // 包裹式 Hook，适合计时/事务
    .stateStore(new RedisAgentStateStore()) // 会话持久化，默认 InMemoryAgentStateStore
    .memory(new RedisMemory())              // 长期记忆（RAG），实现 Memory 接口
    .approvalStore(new RedisApprovalStore())// 审批存储，启用后自动接入审批链路
    .systemMessage("你是客服助手")
    .maxIterations(5)
    .totalTimeoutMs(300_000)
    .toolChoice(ToolChoicePolicy.AUTO)
    .toolAccessPolicy(policy)
    .compactionStrategy(strategy)
    .contentFilter(List.of("敏感词"))
    .enablePlanMode()
    .mcpServer("http://mcp:8080")
    .build();
```

| 参数 | 默认值 |
|---|---|
| `maxIterations` | 10 |
| `totalTimeoutMs` | 300_000（5 分钟） |
| `toolChoice` | AUTO |
| `stateStore` | `InMemoryAgentStateStore` |
| `compactionStrategy` | `ProgressiveCompactionStrategy` |

---

## 自定义工具

### 实现 Tool 接口

```java
public class WeatherTool implements Tool {
    @Override public String getName() { return "weather"; }
    @Override public String getDescription() { return "查询城市天气"; }

    @Override public Mono<ToolResult> execute(ToolCallContext ctx) {
        String city = ctx.getString("city");
        if (city == null) return Mono.just(ToolResult.failure("缺少 city 参数"));
        return Mono.just(ToolResult.success(fetchWeather(city)));
    }
}
```

### 继承 ToolBase（推荐）

```java
public class Calculator extends ToolBase {
    public Calculator() { declareStringParam("expression", "数学表达式", true); }
    @Override public String getName() { return "calculator"; }
    @Override public String getDescription() { return "计算数学表达式"; }

    @Override public Mono<ToolResult> execute(ToolCallContext ctx) {
        validateParams(ctx);
        return Mono.just(ToolResult.success(eval(ctx.getString("expression"))));
    }
}
```

### 注解方式

```java
public class MyTools {
    @ToolFunction(name = "weather", description = "查询天气",
                  timeoutMs = 10000, riskLevel = "LOW")
    public String weather(@ToolParam(name = "city") String city,
                          ToolCallContext ctx) {
        return fetchWeather(city);
    }
}
```

`@ToolFunction` 完整参数：

| 字段 | 默认值 | 说明 |
|---|---|---|
| `name` | 方法名蛇形 | 工具名 |
| `description` | name | 工具描述 |
| `permission` | "" | 业务权限码 |
| `group` | "default" | 分组 |
| `timeoutMs` | 30000 | 超时毫秒 |
| `riskLevel` | "MEDIUM" | LOW/MEDIUM/HIGH/CRITICAL |

---

## 审批流程

工具需要审批时，在 `execute()` 中抛出 `ToolSuspendException`：

```java
public class TransferTool extends ToolBase {
    public TransferTool() {
        declareStringParam("target", "收款账户", true);
        declareNumberParam("amount", "转账金额", true);
    }
    @Override public String getName() { return "transfer"; }
    @Override public String getDescription() { return "转账工具"; }
    @Override public String getRiskLevel() { return "HIGH"; }

    @Override public Mono<ToolResult> execute(ToolCallContext ctx) {
        validateParams(ctx);
        long amount = ctx.getNumber("amount").longValue();

        // 框架重试时 ctx.isApproved()=true，跳过审批
        if (!ctx.isApproved() && amount > 10000) {
            throw new ToolSuspendException("transfer",
                "金额 " + amount + " 超限，是否继续？");
        }
        return Mono.just(ToolResult.success("转账成功: " + amount));
    }
}
```

### 完整审批链路

```
工具抛 ToolSuspendException
  → ReActLoop 捕获 → 查 ApprovalStore.isApproved(sessionId, bypassKey)
  → 未批准 → ON_INTERRUPT → ApprovalHook 创建 PendingApproval → 循环暂停
  → 审批人批准 → ApprovalStore.approve(id)
  → 工具重试 → ctx.isApproved()=true → 跳过审批 → 执行成功
```

### 业务接入

```java
// 实现 ApprovalStore 接口（Redis/MySQL）
public class RedisApprovalStore implements ApprovalStore { ... }

// 注入 Agent
HarnessAgent.builder()
    .approvalStore(new RedisApprovalStore())
    .tool(new TransferTool())
    .build();
```

内置 `list_approvals` 和 `approve_approval` 两个工具，审批人可在 Agent 对话中直接处理审批。

---

## 线程安全

Agent 实例是单例，**所有请求共享同一个实例**。以下组件被并发调用，必须保证线程安全：

| 组件 | 正确做法 |
|---|---|
| `Tool` | 用 `AtomicInteger` / `ConcurrentHashMap` |
| `Hook` / `AroundHook` | 同上 |
| `Memory` | 底层存储本身支持并发（Redis/DB） |
| `AgentStateStore` | 同上 |
| `ApprovalStore` | 同上 |

---

## 其他要点

- **Tool 参数校验** — `ctx.getString("xxx")` 返回 null 时返回 `ToolResult.failure("缺少参数")`，不让 NPE 传播
- **Hook 执行顺序** — 同优先级按注册顺序。建议：KB 拦截（90）→ 内容过滤（50）→ 审批（20）→ 权限（8）→ 审计（100）
- **上下文窗口** — 长对话务必配置 `stateStore` + `compactionStrategy`
- **动态切换模型** — 用 `DynamicChatModel` 包装，运行时 `swap()` 热替换
- **工具超时** — `Tool.getTimeoutMs()` 或 `@ToolFunction(timeoutMs=...)`，默认 30s
- **JSON 容错** — LLM 输出的 malformed JSON（少冒号、代码块包裹等）自动多级修复
- **并行工具调用** — 同一轮多个独立工具调用并发执行（`flatMap`）

---

## Hook 扩展

### 链式 Hook（内置）

```java
.hook(new PermissionHook((tool, ctx) ->
    authService.hasPermission(ctx.getUserId(), tool.getPermissions())))
.hook(new RateLimitHook(20, Duration.ofMinutes(1)))
.hook(new ContentFilterHook(List.of("敏感词")))
.hook(new ApprovalHook(approvalStore))
```

### 自定义 Hook

```java
public class MyHook implements Hook {
    private final AtomicInteger counter = new AtomicInteger(0);

    @Override public String getName() { return "myHook"; }
    @Override public Set<HookEventType> getSubscribedEventTypes() {
        return Set.of(HookEventType.PRE_REASONING);
    }
    @Override public int getPriority() { return 50; }
    @Override public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
        counter.incrementAndGet();
        return Mono.just(HookResult.continue_());
    }
}
```

### 包裹式 Hook

```java
AroundHook timer = new AroundHook() {
    @Override public String getName() { return "timer"; }
    @Override
    public Mono<HookEvent> aroundReasoning(HookEvent e, HookContext ctx,
            Function<HookEvent, Mono<HookEvent>> next) {
        long start = System.currentTimeMillis();
        return next.apply(e).doOnSuccess(ev ->
            System.out.println("耗时 " + (System.currentTimeMillis() - start) + "ms"));
    }
};
```

---

## 流式 SSE 响应

```
POST /api/agent/stream  — 流式（逐 token）
POST /api/agent/chat    — 非流式
POST /api/agent/interrupt — 中断当前执行
```

SSE 事件 type：`text`、`thinking`、`tool_use_start`、`tool_use_delta`，由 `toolUseId` 关联。

---

## 运行时上下文

```java
RuntimeContext ctx = RuntimeContext.builder()
    .tenantId("tenant-A").userId("user-1").sessionId("sess-123").build();

agent.stream(messages, ctx);  // 流式
agent.chat(messages, ctx);    // 非流式
```

---

## 动态切换模型

```java
DynamicChatModel dynamicModel = new DynamicChatModel(
    new DeepSeekChatModel("sk-xxx", "deepseek-chat"));

HarnessAgent.builder().name("agent").model(dynamicModel).build();

dynamicModel.swap(new DashScopeChatModel("sk-yyy", "qwen-plus"));  // 即时生效
```

---

## 长期记忆

```java
public class RedisMemory implements Memory {
    @Override public Mono<String> store(MemoryEntry e) {
        return Mono.fromCallable(() -> redis.set(e.getId(), e.getContent()));
    }
    @Override public Flux<MemoryEntry> retrieve(MemoryRetrievalQuery q) {
        return Flux.fromIterable(redis.search(q.getKeywords()))
            .map(id -> new MemoryEntry(id, redis.get(id)));
    }
    @Override public Mono<MemoryEntry> get(String id) {
        String v = redis.get(id);
        return v != null ? Mono.just(new MemoryEntry(id, v)) : Mono.empty();
    }
    @Override public Mono<Void> forget(String id) {
        return Mono.fromRunnable(() -> redis.del(id));
    }
    @Override public Mono<Void> clear() {
        return Mono.fromRunnable(redis::flushAll);
    }
}
```

---

## 添加新模型

```java
public class MyModel extends ChatModelBase {
    public MyModel(String apiKey, String modelName) {
        super("my-provider", modelName, new OpenAiMessageFormatter());
    }
    @Override protected Map<String, String> buildAuthHeaders() {
        return Map.of("Authorization", "Bearer " + apiKey);
    }
    @Override protected String buildApiUrl() {
        return "https://api.my-provider.com/v1/chat/completions";
    }
}
```
