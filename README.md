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

门面层接口（`ITool`、`IMemory`、`IAgentStateStore`）的方法返回值都是 Reactor 类型。这些接口需要了解几个最基本的用法：

```java
// Mono<T> — 包含 0 或 1 个元素的异步结果
Mono.just(value);           // 成功返回一个值
Mono.just(ToolResult.success("内容"));  // 工具返回成功
Mono.just(ToolResult.failure("原因"));  // 工具返回失败
Mono.empty();               // 返回空（不触发后续操作）
Mono.error(new RuntimeException("err")); // 返回错误
Mono.fromCallable(() -> blockingOperation());  // 包装阻塞调用

// Flux<T> — 包含 0 到 N 个元素的异步流
Flux.just(a, b, c);         // 返回多个元素
Flux.fromIterable(list);    // 从集合创建
Flux.empty();               // 空流
```

### 阻塞转异步

所有接口方法都是异步的，**不要在方法内阻塞等待**。需要调阻塞 API 时，用 `Mono.fromCallable` 包装：

```java
public class RedisMemory implements IMemory {
    @Override public Mono<String> store(MemoryEntry e) {
        return Mono.fromCallable(() -> redis.set(e.getId(), e.getContent()));
    }

    @Override public Flux<MemoryEntry> retrieve(MemoryRetrievalQuery q) {
        List<String> ids = redis.search(q.getKeywords());
        return Flux.fromIterable(ids)
            .map(id -> new MemoryEntry(id, redis.get(id)));
    }
}
```

### 同步返回

数据已经在内存中时，直接用 `Mono.just` / `Flux.just`：

```java
@Override public Mono<Session> findById(SessionId id) {
    Session s = localCache.get(id);
    if (s == null) return Mono.empty();  // 找不到返回空
    return Mono.just(s);
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

    .model(model)                           // 必填。ChatModel 实例。
                                            //   内置：DeepSeekChatModel / OpenAIChatModel / DashScopeChatModel
                                            //   temperature、maxTokens 自动取模型默认值

    .tool(new CalculatorTool())             // 注册工具。可多次调用。三种形式：
                                            //   ① 实现 ITool 接口
                                            //   ② 带 @ToolFunction/@ToolParam 注解的类
                                            //   ③ .mcpServer() 自动注册的远程工具

    .hook(new AuditHook("audit"))           // 注册链式 Hook。可多次调用，
                                            //   按优先级排序（数字越小越先执行）

    .aroundHook(new TimerHook())            // 注册包裹式 Hook。前置+后置在同一作用域，
                                            //   适合跨 PRE/POST 共享状态（计时/事务）

    .stateStore(new RedisAgentStateStore()) // 会话持久化。默认 InMemoryAgentStateStore，
                                            //   生产环境应传外部存储（Redis/MySQL）

    .memory(new RedisMemory())              // 长期记忆（RAG）。实现 IMemory 接口，
                                            //   每轮推理前检索相关记忆注入上下文

    .systemMessage("你是客服助手")          // 系统提示词

    .maxIterations(5)                       // 最大推理轮次，默认 10
    .totalTimeoutMs(300_000)                // 总超时 ms，默认 5 分钟

    .toolChoice(ToolChoicePolicy.AUTO)      // AUTO / REQUIRED / NONE，默认 AUTO
    .toolAccessPolicy(policy)               // 租户级工具访问策略

    .compactionStrategy(strategy)           // 上下文压缩策略，默认 ProgressiveCompactionStrategy
    .contentFilter(List.of("敏感词"))       // 敏感词过滤，自动注入 ContentFilterHook
    .enablePlanMode()                       // 启用计划模式（注入 TodoWriteTool）

    .mcpServer("http://mcp:8080")           // 连接 MCP 服务器，自动发现并注册远程工具
    .mcpServer("http://", "api-key")        // 带鉴权的 MCP 服务器

    .build();
```

**默认值一览：**

| 参数 | 默认值 |
|---|---|
| `maxIterations` | 10 |
| `temperature` | 模型默认值（0.7） |
| `maxTokens` | 模型默认值（4096） |
| `totalTimeoutMs` | 300_000（5 分钟） |
| `toolChoice` | AUTO |
| `stateStore` | `InMemoryAgentStateStore` |
| `compactionStrategy` | `ProgressiveCompactionStrategy` |

---

## 注意事项

### 线程安全

Agent 实例是单例，**所有请求共享同一个实例**。以下组件被所有请求并发调用，实现时必须保证线程安全：

| 组件 | 风险 | 正确做法 |
|---|---|---|
| `ITool` | 工具实例字段被并发修改 | 用 `AtomicInteger` / `ConcurrentHashMap` |
| `Hook` / `AroundHook` | Hook 实例字段被并发修改 | 同上 |
| `IMemory` | `store`/`retrieve` 并发 | 底层存储本身支持并发（Redis/DB） |
| `IAgentStateStore` | `saveCheckpoint`/`addTurn` 并发 | 同上 |

```java
// ❌ 错误 — 并发下计数丢失
public class MyHook implements Hook {
    private int counter = 0;
    public Mono<HookResult> onEvent(...) {
        counter++;  // 非原子操作
        ...
    }
}

// ✅ 正确
public class MyHook implements Hook {
    private final AtomicInteger counter = new AtomicInteger(0);
    public Mono<HookResult> onEvent(...) {
        counter.incrementAndGet();
        ...
    }
}
```

### 其他要点

- **Tool 参数校验** — 执行前必须校验。`ctx.getArgument()` 返回 null 时立即返回 `ToolResult.failure("缺少 xxx 参数")`，不要让 NPE 传播。
- **Hook 执行顺序** — 同优先级按注册顺序。建议：KB 拦截（90）→ 内容过滤（50）→ 权限（8）→ 审计（100）。
- **上下文窗口** — 注意模型 token 上限。长对话务必配置 `stateStore` + `compactionStrategy`。
- **动态切换模型** — 用 `DynamicChatModel` 包装，运行时 `swap()` 热替换，无需重启。

---

## 自定义工具

```java
public class WeatherTool implements ITool {
    @Override public String getName() { return "weather"; }
    @Override public String getDescription() { return "查询城市天气"; }

    @Override public Mono<ToolResult> execute(ToolCallContext ctx) {
        String city = ctx.getArgument("city");
        if (city == null) return Mono.just(ToolResult.failure("缺少 city 参数"));
        String result = fetchWeather(city);
        return Mono.just(ToolResult.success(result));
    }

    @Override public Set<String> getPermissions() { return Set.of("weather:read"); }
}

// 注解方式
public class MyTools {
    @ToolFunction(name = "weather", description = "查询天气")
    public String weather(@ToolParam(name = "city") String city) { ... }
}
```

---

## Hook 扩展

### 链式 Hook（内置，直接注册）

```java
// 权限控制
.hook(new PermissionHook((tool, ctx) ->
    authService.hasPermission(ctx.getUserId(), tool.getPermissions())))

// 频率限制
.hook(new RateLimitHook(20, Duration.ofMinutes(1)))

// 内容过滤
.hook(new ContentFilterHook(List.of("敏感词1", "敏感词2")))

// KB 拦截
ConcurrentHashMap<String, String> kb = new ConcurrentHashMap<>();
kb.put("你好", "你好！有什么可以帮您？");
.hook(new KnowledgeBaseHook((query, ctx) -> kb.get(query)))
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
        return Mono.just(HookResult.continue_());  // continue_ / abort / interrupt / skip
    }
}
```

### 包裹式 Hook

```java
AroundHook timer = new AroundHook() {
    @Override public String getName() { return "timer"; }
    @Override
    public Flux<ChatStreamChunk> aroundReasoningStream(HookEvent e, HookContext ctx,
            Function<HookEvent, Flux<ChatStreamChunk>> next) {
        long start = System.currentTimeMillis();
        return next.apply(e).doOnComplete(() ->
            System.out.println("耗时 " + (System.currentTimeMillis() - start) + "ms"));
    }
};
```

---

## 流式 SSE 响应

```
POST /api/agent/stream  — 流式（逐 token）
POST /api/agent/chat    — 非流式
```

SSE 事件 type：`text`（文本）、`thinking`（思考）、`tool_use_start`（工具开始）、`tool_use_delta`（参数增量），由 `toolUseId` 关联。

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
public class RedisMemory implements IMemory {
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
