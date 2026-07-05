# Agent SDK

面向业务的 AI Agent SDK。依赖 `agent-harness`，通过 Builder 一键装配。

---

## 架构

```
ReActAgent（薄门面）
  ├── AgentConfig（配置注入）
  └── RequestPipeline（请求预处理）
        ├── resolveContext → loadSession → injectSystemMessage → buildLoopCtx
        ├── SessionGate（会话级 FIFO 串行化）
        └── LoopExecutor（Phase-Driven 循环）
              ├── LoopDecisionEngine（纯逻辑状态机，无 Reactor 依赖）
              │     └── Phase.Guard → Reason → Act → Observe → Guard
              ├── ModelCallPipeline（推理 Hook 管线）
              │     └── PRE_REASONING → PRE_MODEL → model.stream → POST_MODEL → POST_REASONING
              └── ToolCallOrchestrator（工具调用编排）
                    └── buildContext → PRE Hook → execute → POST Hook
```

---

## 快速开始

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("MyAgent")
    .model(new DeepSeekChatModel("sk-xxx", "deepseek-chat"))
    .build();

agent.stream(List.of(UserMessage.of("你好")))
    .subscribe(chunk -> System.out.print(chunk.getDelta()));

ChatResponse resp = agent.chat(List.of(UserMessage.of("你好"))).block();
```

---

## Reactor 速查

核心接口（`Tool`、`AgentStateStore`）方法返回值均为 Reactor 类型。

```java
// Mono<T> —— 0 或 1 个元素
Mono.just(value);
Mono.just(ToolResult.success("内容"));
Mono.just(ToolResult.failure("原因"));
Mono.empty();
Mono.error(new RuntimeException("err"));
Mono.fromCallable(() -> blockingOperation());

// Flux<T> —— 0 到 N 个元素
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
return Mono.just(redis.blockingGet(key));  // ❌ 阻塞等待
return null;                                // ❌ 返回 null
return Mono.fromCallable(() -> redis.blockingGet(key));  // ✅ 正确
return Mono.empty();                         // ✅ 正确
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
    .interventionStore(new RedisInterventionStore()) // 介入存储，启用后自动接入人工接管链路
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

| 参数 | 默认值 | 说明 |
|---|---|---|
| `maxIterations` | 10 | 最大 ReAct 迭代次数 |
| `totalTimeoutMs` | 300_000（5 分钟） | 单个请求总超时 |
| `toolChoice` | AUTO | 工具选择策略 |
| `stateStore` | `InMemoryAgentStateStore` | 会话持久化 |
| `compactionStrategy` | `ProgressiveCompactionStrategy` | 上下文压缩 |
| `interventionStore` | `InMemoryInterventionStore` | 人工介入存储 |

---

## 自定义工具

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

### 注解方式

```java
public class MyTools {
    @ToolFunction(name = "weather", description = "查询天气",
                  timeoutMs = 10000, riskLevel = "LOW")
    public String weather(@ToolParam(name = "city") String city, ToolCallContext ctx) {
        return fetchWeather(city);
    }
}
```

`@ToolFunction` 参数：

| 字段 | 默认值 | 说明 |
|---|---|---|
| `name` | 方法名蛇形 | 工具名 |
| `description` | name | 工具描述 |
| `timeoutMs` | 30000 | 超时毫秒 |
| `riskLevel` | "MEDIUM" | LOW / MEDIUM / HIGH / CRITICAL |

---

## 人工介入

统一入口 `HumanInterventionException`，三种类型各走相同的暂停→恢复通道。

```
任意代码抛 HumanInterventionException
  → LoopExecutor catch
  → InterventionStore.create(req)（持久化）
  → ctx.interrupt() 暂停循环
  → HTTP 断连，不占连接

人工解决（API / SDK / 主管 Agent）
  → GET  /api/interventions/pending
  → POST /api/interventions/{id}/resolve { action: "approve" }

同一 session 下一次请求
  → checkpoint 恢复 → 检测已解决
  → approve → 原参数重放工具
  → clarify → 修正参数重放工具
  → deny    → 注入拒绝消息
  → reply   → 注入反馈续跑
```

### 工具审批

```java
public class TransferTool extends ToolBase {
    @Override public Mono<ToolResult> execute(ToolCallContext ctx) {
        validateParams(ctx);
        long amount = ctx.getNumber("amount").longValue();
        if (amount > 10000) {
            throw HumanInterventionException.approval("transfer",
                "金额 " + amount + " 超限，是否继续？", ctx);
        }
        return Mono.just(ToolResult.success("转账成功: " + amount));
    }
}
```

### 参数澄清

```java
throw HumanInterventionException.clarify("transfer",
    "收款人不明确，请指定", ctx);
// 人工修正参数 → 以 modifiedArgs 重放
```

### 业务暂停

```java
// 任意代码（Hook、工具、业务逻辑）
throw HumanInterventionException.pause("检测到异常，需要确认");
// 人工 reply → 注入反馈消息 → 循环继续
```

### 业务接入

```java
// 实现 InterventionStore 接口
public class RedisInterventionStore implements InterventionStore { ... }

HarnessAgent.builder()
    .interventionStore(new RedisInterventionStore())
    .build();
```

内置 `list_interventions` 和 `resolve_intervention` 两个主管工具。

---

## 会话级串行化

`SessionGate` 确保同一 session 的请求 FIFO 排队执行，不同 session 并发。

```
请求 A（session=abc）→ 立即执行 → 发出完成信号
请求 B（session=abc）→ 等待 A 完成 → 执行
请求 C（session=xyz）→ 立即执行（不同 session，不受影响）
```

基于 `ConcurrentHashMap.put`（原子替换）+ `Sinks.One`（异步信号），无锁实现。

---

## JSON 容错

`JsonUtils.repairJson` 多级修复 LLM 输出格式错误：

1. 尾部逗号：`{"x": "y",}` → `{"x": "y"}`
2. 键名缺引号：`{key: value}` → `{"key": value}`
3. 缺失冒号：`"key""value"` → `"key":"value"`
4. 代码块包裹：` ```json {...} ``` `
5. 尾部多余文本：`{...} trailing text`
6. 未闭合字符串：`{"x": "y}` → `{"x": "y"}`
7. 正则兜底：从混乱文本中提取 key-value 对

---

## 线程安全

Agent 实例是单例，**所有请求共享同一个实例**。以下组件被并发调用，必须保证线程安全：

| 组件 | 正确做法 |
|---|---|
| `Tool` | `AtomicInteger` / `ConcurrentHashMap` |
| `Hook` / `AroundHook` | 同上 |
| `Memory` | 底层存储本身支持并发（Redis / DB） |
| `AgentStateStore` | 同上 |
| `InterventionStore` | 同上 |

---

## Hook 扩展

### 链式 Hook

```java
.hook(new PermissionHook((tool, ctx) ->
    authService.hasPermission(ctx.getUserId(), tool.getPermissions())))
.hook(new RateLimitHook(20, Duration.ofMinutes(1)))
.hook(new ContentFilterHook(List.of("敏感词")))
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
    @Override public Mono<HookResult> onEvent(HookEvent event, HookContext ctx) {
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
POST /api/agent/session/{id}/history — 会话历史
```

SSE 事件 type：`text`、`thinking`、`tool_use_start`、`tool_use_delta`、`intervention`，各由 `toolUseId` / `interventionId` 关联。

### 介入 REST API

```
GET  /api/interventions/pending          — 所有待处理
GET  /api/interventions/pending/{session} — 按会话查询
GET  /api/interventions/{id}             — 详情
POST /api/interventions/{id}/resolve     — 解决（approve / deny / clarify / reply）
```

---

## 运行时上下文

```java
RuntimeContext ctx = RuntimeContext.builder()
    .tenantId("tenant-A").userId("user-1").sessionId("sess-123").build();

agent.stream(messages, ctx);
agent.chat(messages, ctx);
```

---

## 动态切换模型

```java
DynamicChatModel dynamicModel = new DynamicChatModel(
    new DeepSeekChatModel("sk-xxx", "deepseek-chat"));

HarnessAgent.builder().name("agent").model(dynamicModel).build();
dynamicModel.swap(new DashScopeChatModel("sk-yyy", "qwen-plus"));
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
    @Override public Mono<Void> clear() { return Mono.fromRunnable(redis::flushAll); }
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

---

## 常量规范

所有字符串字面量集中在 `CoreConstants`：
- `RuntimeCtx` — Reactor Context 键
- `EventPayload` — HookEvent payload 键
- `FinishReason` — LLM 响应结束原因
- `JsonSchema` — JSON Schema 字段名
- `Intervention` — 人工介入常量
- `Validation` — 参数校验名
- `UI` — 用户可见消息
- `Prompt` — 系统提示词模板

代码中禁止裸字符串，统一引用 `CoreConstants` 静态字段。

---

## 注释规范

所有方法、字段使用 `/** \n * \n */` 中文 Javadoc，禁止 `//` 行注释和英文注释。`@param`、`@return` 写出语义。
