# HarnessAgent 框架设计规格

**状态**：已确认
**日期**：2026-06-22
**参考**：AgentScope Java (https://github.com/agentscope-ai/agentscope-java)

## 1. 概述

HarnessAgent 是一个多租户、响应式的 Agent 框架，用于构建 LLM 驱动的智能应用。提供完整的 Hook 体系、Tool 体系、Session 持久化、多模型提供商支持。项目采用 3 模块 Maven 结构，附带 Vue 测试页面。

### 1.1 模块职责

| 模块 | 职责 |
|------|------|
| **agent-core** | 纯 Java + Reactor 领域核心，零 Spring 依赖 |
| **agent-bootstrap** | Spring Boot 装配层（依赖注入、Web、自动配置） |
| **agent-harness** | 对外 SDK 门面，Builder API + 注解 + SPI |

### 1.2 关键设计决策

- **不使用 `record` 语法** — 全部使用传统 Java 类
- **JSON 序列化** — com.alibaba.fastjson2（不使用 Jackson）
- **响应式** — Project Reactor（Mono/Flux）处理所有异步操作
- **命名** — 使用 AbstractAgent 而非 AgentBase/ReActAgent，避免与 AgentScope 重名
- **多租户** — SaaS 级 RBAC，租户级数据隔离
- **数据库** — MySQL 存储会话、记忆、凭证
- **构建工具** — Maven 多模块

## 2. 包依赖关系

```
agent-harness  ──→ agent-core  ←──  agent-bootstrap (Spring Boot)
     ↑                                    ↑
     └── 注解 + SPI + DTO                  └── REST 控制器 + 依赖注入 + 自动配置
```

- `agent-core` 仅依赖：Reactor Core、Reactor Netty、fastjson2、MySQL Connector
- `agent-bootstrap` 增加：Spring Boot Starter Web、Spring Boot Starter Validation
- `agent-harness` 仅依赖：agent-core

## 3. 模块与包结构

### 3.1 agent-core（`cd.lan1akea.core`）

#### 3.1.1 agent/ — Agent 抽象与 ReAct 循环

```
agent/
├── Agent.java                    // Agent 顶层接口
├── AbstractAgent.java            // 抽象基类（模板方法模式）
├── ObservableAgent.java          // 可观测能力接口
├── StreamableAgent.java          // 流式能力接口
├── CallableAgent.java            // 同步调用能力接口
├── AgentEvent.java               // Agent 生命周期事件
├── AgentEventType.java           // 事件类型枚举
├── AgentEventSource.java         // 事件源
├── AgentStreamOptions.java       // 流式选项
├── AgentStreamingHook.java       // 流式 Hook 接口
├── AgentSubEventBus.java         // 子 Agent 事件总线
├── config/
│   ├── AgentConfig.java          // Agent 配置
│   ├── AgentExecutionConfig.java // 执行配置（超时、重试、温度等）
│   ├── AgentGenerateOptions.java // 生成选项
│   └── AgentToolChoice.java      // 工具选择策略
└── loop/
    ├── ReActLoop.java            // ReAct 循环引擎
    ├── LoopContext.java          // 循环上下文
    ├── LoopStep.java             // 单步抽象
    ├── ReasoningStep.java        // 推理步骤
    ├── ActingStep.java           // 行动步骤
    └── ObservationStep.java      // 观察步骤
```

- **Agent**：顶层接口，定义 `chat()`、`stream()`、`session()` 三个入口
- **AbstractAgent**：模板方法基类，持有 ReActLoop、ModelProvider、ToolRegistry、HookChain、MiddlewareChain
- **ReActLoop**：驱动 Reasoning（推理）→ Acting（工具调用）→ Observation（结果观察）循环，支持最大迭代次数和提前退出

#### 3.1.2 message/ — 消息类型与内容块

```
message/
├── Msg.java                      // 消息顶层类
├── MsgRole.java                  // 角色枚举（SYSTEM、USER、ASSISTANT、TOOL）
├── MsgBuilder.java               // 消息构建器
├── SystemMessage.java            // 系统消息
├── UserMessage.java              // 用户消息
├── AssistantMessage.java         // 助手消息
├── ToolResultMessage.java        // 工具结果消息
├── ContentBlock.java             // 内容块抽象
├── TextBlock.java                // 文本内容块
├── ImageBlock.java               // 图片内容块
├── AudioBlock.java               // 音频内容块
├── VideoBlock.java               // 视频内容块
├── ThinkingBlock.java            // 思考过程内容块
├── ToolUseBlock.java             // 工具调用内容块
├── ToolResultBlock.java          // 工具结果内容块
├── DataBlock.java                // 通用数据块
├── Source.java                   // 来源抽象
├── UrlSource.java                // URL 来源
├── Base64Source.java             // Base64 来源
├── HintBlock.java                // 提示块
└── MessageMetadataKeys.java      // 元数据键常量
```

- **Msg**：包裹角色 + ContentBlock 列表，支持 Builder 模式构建，附带 metadata 扩展字段
- **ContentBlock**：多态内容单元 — 文本、图片（URL/Base64）、音频、视频、工具调用、工具结果、思考轨迹
- 各消息类型（SystemMessage、UserMessage、AssistantMessage、ToolResultMessage）继承 Msg 并约束角色

#### 3.1.3 model/ — LLM 提供商抽象

```
model/
├── Model.java                    // 模型顶层接口
├── ChatModel.java                // 聊天模型接口
├── ChatModelBase.java            // 聊天模型抽象基类
├── ChatResponse.java             // 聊天响应
├── ChatStreamChunk.java          // 流式响应块
├── ChatUsage.java                // Token 用量
├── ModelException.java           // 模型异常
├── ModelRegistry.java            // 模型注册表
├── ModelUtils.java               // 模型工具方法
├── ModelContextWindow.java       // 上下文窗口配置
├── StructuredOutputReminder.java // 结构化输出提醒
├── ToolSchema.java               // 工具 Schema 定义
├── ToolChoicePolicy.java         // 工具选择策略
├── GenerateOptions.java          // 生成选项
├── EndpointType.java             // 端点类型（CHAT、EMBEDDING 等）
├── openai/
│   ├── OpenAIChatModel.java      // OpenAI 聊天模型
│   ├── OpenAIClient.java         // OpenAI HTTP 客户端
│   └── OpenAIHttpTransport.java  // OpenAI 传输层
├── deepseek/
│   ├── DeepSeekChatModel.java    // DeepSeek 聊天模型
│   └── DeepSeekClient.java       // DeepSeek HTTP 客户端
├── dashscope/
│   ├── DashScopeChatModel.java   // 百炼平台聊天模型
│   ├── DashScopeClient.java      // 百炼 HTTP 客户端
│   └── DashScopeEncryptionUtils.java // 百炼加密工具
├── custom/
│   ├── CustomChatModel.java      // 自定义提供商基类
│   └── CustomProviderAdapter.java // 自定义适配器
└── transport/
    ├── HttpClientAdapter.java    // HTTP 客户端适配接口
    ├── ReactorHttpClientAdapter.java // Reactor HTTP 适配器
    └── SseEventParser.java       // SSE 事件解析器
```

- **ChatModel**：核心接口 — `chat(List<Msg>, GenerateOptions) → Mono<ChatResponse>`、`stream(...) → Flux<ChatStreamChunk>`
- **ChatModelBase**：共享逻辑 — 消息格式化（调用 Formatter）、重试、限流、结构化输出处理
- **ModelRegistry**：映射 providerName → ChatModel 实现，支持动态注册
- 每个提供商有独立子包，包含 ChatModel + Client 类
- **CustomChatModel**：第三方自定义提供商的基类，基于 endpoint URL + API Key 模式

#### 3.1.4 tool/ — 工具系统

```
tool/
├── Tool.java                     // 工具顶层接口
├── ToolBase.java                 // 工具抽象基类
├── ToolParam.java                // 工具参数定义
├── ToolCallParam.java            // 工具调用参数
├── ToolSchemaProvider.java       // 工具 Schema 提供者接口
├── ToolSchemaGenerator.java      // 从注解/反射生成 Schema
├── ToolSchemaModule.java         // JSON Schema 模块
├── ToolValidator.java            // 参数校验器
├── ToolExecutor.java             // 工具执行器
├── ToolEmitter.java              // 工具事件发射器接口
├── DefaultToolEmitter.java       // 默认事件发射器
├── NoOpToolEmitter.java          // 空事件发射器
├── ToolRegistry.java             // 工具注册表
├── ToolGroup.java                // 工具组
├── ToolGroupScope.java           // 工具组作用域
├── ToolGroupManager.java         // 工具组管理器
├── ToolResultConverter.java      // 结果转换器接口
├── DefaultToolResultConverter.java // 默认结果转换器
├── ToolResultMessageBuilder.java // 结果消息构建器
├── ToolExecutionContext.java     // 工具执行上下文
├── ToolExecutionContextProvider.java // 工具执行上下文提供者
├── ToolMethodInvoker.java        // 反射方法调用器
├── RegisteredToolFunction.java   // 已注册工具函数描述符
├── ToolSuspendException.java     // 工具暂停异常（用于人工干预）
├── MetaToolFactory.java          // 元工具工厂
├── AgentTool.java                // 将 Agent 包装为 Tool
├── SchemaOnlyTool.java           // Schema 占位（MCP 预留）
├── SkillToolGroup.java           // Skill 工具组（预留）
├── builtin/
│   ├── CalculatorTool.java       // 数学表达式计算
│   ├── WebSearchTool.java        // 网络搜索
│   ├── HttpClientTool.java       // HTTP 客户端
│   ├── FileReadTool.java         // 文件读取
│   ├── FileWriteTool.java        // 文件写入
│   ├── CodeInterpreterTool.java  // 代码解释器（沙箱内执行）
│   └── DatabaseQueryTool.java    // 数据库查询
├── memory/
│   ├── MemoryStoreTool.java      // 记忆存储
│   ├── MemoryRetrieveTool.java   // 记忆检索
│   └── MemoryForgetTool.java     // 记忆遗忘
├── planning/
│   ├── PlanNotebookTool.java     // 任务分解与追踪
│   └── PlanStepTool.java         // 步骤管理
└── mcp/
    └── McpToolAdapter.java       // MCP 工具适配器（预留）
```

- **Tool**：核心接口 — `name()`、`description()`、`parameters()`（返回 ToolSchema）、`execute(ToolCallParam) → Mono<ToolResult>`
- **ToolRegistry**：按名称查找工具，支持基于分组的范围控制
- **ToolExecutor**：调用工具、错误处理、通过 ToolEmitter 发射事件、捕获 ToolSuspendException
- **ToolGroup**：命名工具集合，作用域为 TENANT、USER、SESSION、GLOBAL
- **SchemaOnlyTool**：占位工具，实现后续提供（MCP/Skill），先暴露 Schema 给 LLM

#### 3.1.5 hook/ — Hook 体系

```
hook/
├── Hook.java                     // Hook 顶层接口
├── HookEvent.java                // Hook 事件抽象
├── HookEventType.java            // Hook 事件类型枚举
├── HookChain.java                // Hook 链（责任链模式）
├── HookDispatcher.java           // Hook 调度器
├── HookContext.java              // Hook 执行上下文
├── RuntimeContextAware.java      // 运行时上下文感知接口
├── PreReasoningHook.java         // LLM 推理前 Hook
├── PostReasoningHook.java        // LLM 推理后 Hook
├── PreActingHook.java            // 行动执行前 Hook
├── PostActingHook.java           // 行动执行后 Hook
├── PreToolCallHook.java          // 工具调用前 Hook
├── PostToolCallHook.java         // 工具调用后 Hook
├── ErrorHook.java                // 错误 Hook
├── InterruptHook.java            // 中断 Hook（人机协同）
├── StreamingChunkHook.java       // 流式块 Hook
├── SummaryHook.java              // 摘要 Hook
├── ReasoningEvent.java           // 推理事件
├── ActingEvent.java              // 行动事件
├── ErrorEvent.java               // 错误事件
├── InterruptEvent.java           // 中断事件
└── recorder/
    └── HookRecorder.java         // Hook 记录器（审计/回放）
```

- **Hook**：核心接口 — `onEvent(HookEvent, HookContext) → Mono<HookResult>`
- **HookEventType**：PRE_REASONING、POST_REASONING、PRE_ACTING、POST_ACTING、PRE_TOOL_CALL、POST_TOOL_CALL、ON_ERROR、ON_INTERRUPT、ON_STREAM_CHUNK、ON_SUMMARY
- **HookChain**：有序 Hook 列表，通过 `Mono.flatMap` 链式顺序执行
- **HookResult**：CONTINUE（放行）、MODIFY（修改输入/输出）、INTERRUPT（暂停等待人工）、ABORT（终止执行）
- **InterruptHook + InterruptEvent**：实现人机协同 — Hook 暂停 Agent，Controller 暴露干预接口，人工输入注入后继续执行

#### 3.1.6 tenant/ — 多租户 RBAC

```
tenant/
├── Tenant.java                   // 租户实体
├── TenantId.java                 // 租户标识
├── TenantContext.java            // 当前租户上下文
├── TenantContextHolder.java      // 租户上下文持有者
├── TenantIsolationValidator.java // 数据隔离校验器
├── User.java                     // 用户实体
├── UserId.java                   // 用户标识
├── Role.java                     // 角色实体
├── Permission.java               // 权限实体
├── PermissionDecision.java       // 权限决策结果
├── PermissionEngine.java         // 权限评估引擎
├── PermissionRule.java           // 权限规则
├── PermissionBehavior.java       // 权限行为（ALLOW/DENY/ASK）
├── PermissionMode.java           // 权限模式（STRICT/PERMISSIVE）
└── ResourceType.java             // 资源类型枚举（TOOL、SESSION、MEMORY、CREDENTIAL）
```

- **TenantContextHolder**：通过 Reactor Context（`Mono.deferContextual()`）传递租户信息，不使用 ThreadLocal（保证响应式安全）
- **PermissionEngine**：评估 (租户, 用户, 角色, 资源, 操作) → PermissionDecision
- **TenantIsolationValidator**：所有数据访问自动拼接 `WHERE tenant_id = ?`，每次数据访问校验

#### 3.1.7 session/ — 会话与聊天管理

```
session/
├── Session.java                  // 会话实体
├── SessionId.java                // 会话标识
├── SessionState.java             // 会话状态枚举
├── SessionStore.java             // 会话存储接口
├── MysqlSessionStore.java        // MySQL 会话存储
├── InMemorySessionStore.java     // 内存会话存储（测试用）
├── Chat.java                     // 单次聊天
├── ChatTurn.java                 // 对话轮次
└── SessionSummaryService.java    // 会话摘要服务
```

- **Session**：多轮对话容器，按租户隔离
- **Chat**：一次完整聊天（用户输入 → Agent 输出，含多轮 ReAct）
- **ChatTurn**：一个请求-响应对，包含用户消息、助手消息、工具调用及结果
- **SessionStore**：接口 — `create()`、`findById()`、`addTurn()`、`listByTenant()`、`close()`

#### 3.1.8 context/ — 运行时上下文

```
context/
├── RuntimeContext.java           // 通用运行时上下文
├── AgentContext.java             // Agent 执行上下文
├── ToolCallContext.java          // 工具调用上下文
├── SessionContext.java           // 会话上下文
└── ContextStore.java             // 上下文存储
```

- **AgentContext**：当前 Agent 的名称、模型配置、工具集、Hook 链
- **ToolCallContext**：当前工具名、参数、调用者身份
- **SessionContext**：会话 ID、租户、用户、累计 Token 数

#### 3.1.9 event/ — 事件总线基础设施

```
event/
├── DomainEvent.java              // 领域事件基类
├── EventPublisher.java           // 事件发布器
├── EventSubscriber.java          // 事件订阅器
└── EventBus.java                 // 事件总线
```

- **EventBus**：基于 Reactor `Sinks.Many`，支持每种事件类型多个订阅者
- 领域特有事件（AgentEvent、ToolExecutionEvent 等）留在各自的领域包中

#### 3.1.10 memory/ — 长期记忆

```
memory/
├── Memory.java                   // 记忆接口
├── LongTermMemory.java           // 长期记忆接口
├── LongTermMemoryMode.java       // 记忆模式枚举
├── MemoryStore.java              // 记忆存储接口
├── MysqlMemoryStore.java         // MySQL 记忆存储
├── InMemoryMemoryStore.java      // 内存记忆存储
├── MemoryEntry.java              // 记忆条目
├── MemoryRetrievalQuery.java     // 记忆检索查询
├── MemoryRetrievalResult.java    // 记忆检索结果
├── EmbeddingService.java         // 向量嵌入服务接口（预留）
└── OpenAiEmbeddingService.java   // OpenAI 嵌入实现
```

- **LongTermMemory**：继承 Memory，提供 `store()`、`retrieve(query)`、`forget(id)`、`summarize()`
- **MemoryStore**：持久化记忆条目，按租户+用户隔离
- **EmbeddingService**：语义搜索预留，初期使用 MySQL FULLTEXT 索引

#### 3.1.11 state/ — 检查点与恢复

```
state/
├── AgentState.java               // Agent 状态
├── StateStore.java               // 状态存储接口
├── InMemoryStateStore.java       // 内存状态存储
├── MysqlStateStore.java          // MySQL 状态存储
├── CheckpointService.java        // 检查点创建/恢复
└── SessionStateSerializer.java   // 状态序列化工具
```

- **AgentState**：Agent 执行快照 — 当前循环迭代、消息历史、工具状态
- **CheckpointService**：`saveCheckpoint(agentId, state)` 和 `restoreCheckpoint(agentId) → AgentState`
- 支持暂停/恢复和崩溃恢复

#### 3.1.12 middleware/ — 请求/响应管道

```
middleware/
├── Middleware.java               // 中间件接口
├── MiddlewareChain.java          // 中间件链
├── LoggingMiddleware.java        // 请求/响应日志
├── RateLimitingMiddleware.java   // 限流
└── RetryMiddleware.java          // 暂态故障重试
```

- **Middleware**：`before(Context) → Mono<Context>` 和 `after(Context) → Mono<Context>`
- 在 Agent 核心调用前后链式执行

#### 3.1.13 formatter/ — 消息格式化

```
formatter/
├── MessageFormatter.java         // 格式化器接口
├── OpenAiMessageFormatter.java   // OpenAI 格式
├── DeepSeekMessageFormatter.java // DeepSeek 格式
└── DashScopeMessageFormatter.java // 百炼格式
```

- **MessageFormatter**：将内部 `Msg` 列表转换为各厂商 API 要求的 JSON 结构

#### 3.1.14 辅助包

```
shutdown/
├── GracefulShutdown.java         // 优雅停机协调器
├── ShutdownHook.java             // 停机 Hook 接口
└── ShutdownSignal.java           // 停机信号

credential/
├── Credential.java               // 凭证接口
├── ApiKeyCredential.java         // API Key 凭证
├── TenantCredentialStore.java    // 租户凭证存储
└── EncryptedCredentialStore.java // 加密凭证存储

exception/
├── AgentException.java           // 框架基础异常
├── AgentConfigurationException.java
├── ModelCallException.java
├── ToolExecutionException.java
├── HookAbortException.java
├── TenantIsolationException.java
├── SessionNotFoundException.java
└── PermissionDeniedException.java

workspace/
└── Workspace.java                // 文件沙箱工作空间

tracing/
├── TraceSpan.java                // 追踪 Span
├── TraceContext.java             // 追踪上下文
└── OpenTelemetryAdapter.java     // OpenTelemetry 适配器

util/
├── JsonUtils.java                // fastjson2 工具
├── IdGenerator.java              // ID 生成器（雪花算法）
├── StringUtils.java              // 字符串工具
└── ValidationUtils.java          // 校验工具
```

### 3.2 agent-bootstrap（`cd.lan1akea.bootstrap`）

```
bootstrap/
├── BootstrapApplication.java         // Spring Boot 入口
├── config/
│   ├── AgentAutoConfiguration.java       // 核心自动装配
│   ├── ModelProviderConfig.java          // 提供商 Bean 定义
│   ├── ToolRegistryConfig.java           // 工具注册 Bean
│   ├── HookChainConfig.java              // Hook 链 Bean
│   ├── TenantConfig.java                 // 多租户配置
│   ├── PersistenceConfig.java            // 数据源 + 事务配置
│   └── WebMvcConfig.java                // Web MVC 配置
├── controller/
│   ├── AgentController.java             // Chat + Stream API
│   ├── SessionController.java           // Session 管理 API
│   ├── TenantAdminController.java       // 租户管理 API
│   ├── ToolController.java              // 工具发现与管理 API
│   ├── HookInterventionController.java  // 人工干预 API
│   └── HealthController.java            // 健康检查 API
└── web/
    └── (Vite + Vue 3 前端，构建后输出到 static/)
```

### 3.3 agent-harness（`cd.lan1akea.harness`）

```
harness/
├── HarnessAgent.java             // 门面类（一行创建 Agent）
├── HarnessAgentBuilder.java      // Builder 流式 API
├── HarnessConfig.java            // 全局配置
├── HarnessException.java         // SDK 异常
├── annotation/
│   ├── ToolFunction.java         // 标记类/方法为工具函数
│   ├── ToolParam.java            // 参数元数据注解
│   ├── ToolSchema.java           // 自定义 Schema 覆盖注解
│   └── HookSubscribe.java        // Hook 订阅注解
├── api/
│   ├── ChatRequest.java          // 请求 DTO
│   ├── ChatResponse.java         // 响应 DTO
│   ├── SessionInfo.java          // 会话信息 DTO
│   ├── ToolInfo.java             // 工具信息 DTO
│   └── TenantInfo.java           // 租户信息 DTO
└── spi/
    ├── ModelProviderSpi.java     // 模型提供商 SPI
    ├── ToolProviderSpi.java      // 工具提供商 SPI
    ├── HookProviderSpi.java      // Hook 提供商 SPI
    └── StorageProviderSpi.java   // 存储提供商 SPI
```

### 3.4 harness 注解使用示例

第三方通过注解声明工具：

```java
// 注解声明工具类
@ToolFunction(name = "weather", description = "查询天气")
public class WeatherTool extends ToolBase {

    @ToolParam(name = "city", description = "城市名", required = true)
    private String city;

    @Override
    public Mono<ToolResult> execute(ToolCallParam params) {
        // 实现逻辑
    }
}

// 通过 Builder 注册
HarnessAgent.builder()
    .name("MyAgent")
    .tool(new WeatherTool())           // 自动扫描 @ToolFunction + @ToolParam 生成 Schema
    .toolPackage("com.example.tools")  // 包扫描方式批量注册
    .hook(new AuditHook())             // @HookSubscribe 标注的 Hook
    .build();
```

## 4. 核心数据流

### 4.1 单次 Chat 流程

```
HarnessAgent.chat(ChatRequest)
  │
  ├─ 1. 从请求解析 TenantContext → 注入 Reactor Context
  ├─ 2. Session 查找/创建（如有 sessionId，加载历史消息）
  ├─ 3. MiddlewareChain.before(ctx)
  ├─ 4. 构建消息列表：system prompt + 历史 + 当前 user message
  ├─ 5. ReActLoop.execute(messages, config)
  │     │
  │     ├─ Reasoning（推理阶段）:
  │     │     HookChain.fire(PreReasoning)  // 推理前 Hook
  │     │     → ModelProvider.chat()        // 调用 LLM
  │     │     → HookChain.fire(PostReasoning) // 推理后 Hook
  │     │
  │     ├─ [如果响应包含工具调用]:
  │     │     Acting（行动阶段）:
  │     │       PermissionEngine.check(当前用户, 工具, 参数) // 权限校验
  │     │       HookChain.fire(PreToolCall)    // 工具调用前 Hook
  │     │       ToolExecutor.execute(tool, params)
  │     │         └─ [如果 ToolSuspendException → InterruptHook, 等待人工]
  │     │       HookChain.fire(PostToolCall)   // 工具调用后 Hook
  │     │     Observation（观察阶段）: 工具结果追加到消息列表
  │     │     回到 Reasoning 循环
  │     │
  │     └─ [如果无工具调用]: 返回最终响应
  │
  ├─ 6. MiddlewareChain.after(ctx)
  ├─ 7. ChatTurn 持久化到 Session
  ├─ 8. 返回 ChatResponse
```

### 4.2 流式流程

与 Chat 流程相同，但 ModelProvider 返回 `Flux<ChatStreamChunk>`，每个 chunk 经过 StreamingChunkHook，通过 SSE 推送给客户端。

### 4.3 人机协同流程

```
ToolExecutor.execute(tool, params)
  → tool 抛出 ToolSuspendException("文件写入需要人工审批")
  → ToolExecutor 捕获 → 发射 InterruptEvent
  → InterruptHook.onEvent(InterruptEvent)
  → Hook 设置状态为 AWAITING_INPUT，保存 interruptId
  → Controller 返回 ChatResponse(status=INTERRUPTED, interruptId=xxx)
  → 人工审核 → POST /api/hook/intervene {interruptId, decision, input}
  → HookChain 恢复 → ToolExecutor 以人工提供的结果完成
  → Agent 继续执行
```

## 5. REST API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/agent/chat` | 单次对话 |
| POST | `/api/agent/stream` | 流式对话（SSE） |
| POST | `/api/session` | 创建会话 |
| GET | `/api/session/{id}` | 获取会话详情 |
| GET | `/api/session/{id}/history` | 获取会话历史消息 |
| DELETE | `/api/session/{id}` | 关闭会话 |
| GET | `/api/tools` | 列出可用工具 |
| POST | `/api/tools/register` | 注册自定义工具 |
| POST | `/api/hook/intervene` | 人工干预响应 |
| GET | `/api/hook/events/{interruptId}` | 获取待处理干预 |
| POST | `/api/tenant` | 创建租户 |
| GET | `/api/tenant/{id}` | 获取租户信息 |
| GET | `/api/health` | 健康检查 |

## 6. 数据库设计（MySQL）

```sql
-- 租户表
CREATE TABLE t_tenant (
    id          BIGINT PRIMARY KEY COMMENT '租户ID',
    name        VARCHAR(128) NOT NULL COMMENT '租户名称',
    status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/SUSPENDED',
    quota_json  JSON COMMENT '配额配置',
    created_at  DATETIME NOT NULL COMMENT '创建时间',
    updated_at  DATETIME NOT NULL COMMENT '更新时间'
) ENGINE=InnoDB COMMENT '租户表';

-- 租户用户表
CREATE TABLE t_tenant_user (
    id             BIGINT PRIMARY KEY COMMENT '用户ID',
    tenant_id      BIGINT NOT NULL COMMENT '所属租户ID',
    username       VARCHAR(64) NOT NULL COMMENT '用户名',
    password_hash  VARCHAR(256) NOT NULL COMMENT '密码哈希',
    status         VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态',
    created_at     DATETIME NOT NULL COMMENT '创建时间',
    UNIQUE KEY uk_tenant_username (tenant_id, username)
) ENGINE=InnoDB COMMENT '租户用户表';

-- 用户角色关联表
CREATE TABLE t_user_role (
    id      BIGINT PRIMARY KEY COMMENT '主键',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    role_id BIGINT NOT NULL COMMENT '角色ID'
) ENGINE=InnoDB COMMENT '用户角色关联表';

-- 角色权限关联表
CREATE TABLE t_role_permission (
    id            BIGINT PRIMARY KEY COMMENT '主键',
    role_id       BIGINT NOT NULL COMMENT '角色ID',
    permission    VARCHAR(128) NOT NULL COMMENT '权限标识'
) ENGINE=InnoDB COMMENT '角色权限关联表';

-- 凭证表（加密存储各提供商 API Key）
CREATE TABLE t_credential (
    id                BIGINT PRIMARY KEY COMMENT '凭证ID',
    tenant_id         BIGINT NOT NULL COMMENT '租户ID',
    provider          VARCHAR(32) NOT NULL COMMENT '提供商: openai/deepseek/dashscope',
    encrypted_api_key VARCHAR(512) NOT NULL COMMENT '加密后的API Key',
    created_at        DATETIME NOT NULL COMMENT '创建时间',
    UNIQUE KEY uk_tenant_provider (tenant_id, provider)
) ENGINE=InnoDB COMMENT '凭证表';

-- 会话表
CREATE TABLE t_session (
    id         BIGINT PRIMARY KEY COMMENT '会话ID',
    tenant_id  BIGINT NOT NULL COMMENT '租户ID',
    agent_name VARCHAR(128) COMMENT 'Agent名称',
    status     VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/PAUSED/CLOSED',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    updated_at DATETIME NOT NULL COMMENT '更新时间',
    KEY idx_tenant (tenant_id)
) ENGINE=InnoDB COMMENT '会话表';

-- 对话轮次表
CREATE TABLE t_chat_turn (
    id                  BIGINT PRIMARY KEY COMMENT '轮次ID',
    session_id          BIGINT NOT NULL COMMENT '会话ID',
    turn_order          INT NOT NULL COMMENT '轮次序号',
    user_msg_json       JSON NOT NULL COMMENT '用户消息JSON',
    assistant_msg_json  JSON COMMENT '助手消息JSON',
    tool_calls_json     JSON COMMENT '工具调用JSON',
    created_at          DATETIME NOT NULL COMMENT '创建时间',
    KEY idx_session_turn (session_id, turn_order)
) ENGINE=InnoDB COMMENT '对话轮次表';

-- 记忆条目表
CREATE TABLE t_memory_entry (
    id              BIGINT PRIMARY KEY COMMENT '记忆ID',
    tenant_id       BIGINT NOT NULL COMMENT '租户ID',
    user_id         BIGINT COMMENT '用户ID',
    content         TEXT NOT NULL COMMENT '记忆内容',
    embedding_json  JSON COMMENT '向量嵌入JSON',
    metadata_json   JSON COMMENT '元数据JSON',
    created_at      DATETIME NOT NULL COMMENT '创建时间',
    KEY idx_tenant (tenant_id),
    FULLTEXT KEY ft_content (content)
) ENGINE=InnoDB COMMENT '记忆条目表';

-- Agent 状态检查点表
CREATE TABLE t_agent_state (
    id          BIGINT PRIMARY KEY COMMENT '状态ID',
    tenant_id   BIGINT NOT NULL COMMENT '租户ID',
    session_id  BIGINT COMMENT '会话ID',
    agent_name  VARCHAR(128) COMMENT 'Agent名称',
    state_json  JSON NOT NULL COMMENT '状态快照JSON',
    created_at  DATETIME NOT NULL COMMENT '创建时间',
    KEY idx_session (session_id)
) ENGINE=InnoDB COMMENT 'Agent状态检查点表';
```

## 7. 前端测试页面

- **技术栈**：Vite + Vue 3，构建后输出到 `agent-bootstrap/src/main/resources/static/`
- **Tab 结构**：聊天 | 会话 | 工具 | Hook | 租户管理
- **聊天 Tab**：文本输入框、流式开关、工具调用可视化、干预对话框
- **会话 Tab**：会话列表、按角色着色的历史消息查看器
- **工具 Tab**：可用工具网格、注册新工具表单
- **Hook Tab**：活跃 Hook 列表、待处理干预项
- **租户 Tab**：租户 CRUD、用户管理

## 8. 关键代码骨架

### 8.1 AbstractAgent（core 层）

```java
// Agent 抽象基类，模板方法定义 Agent 生命周期
public abstract class AbstractAgent implements Agent, ObservableAgent, StreamableAgent {
    // Agent 名称
    private final String name;
    // 模型提供商
    private final ChatModel model;
    // 工具注册表
    private final ToolRegistry toolRegistry;
    // Hook 链
    private final HookChain hookChain;
    // 中间件链
    private final MiddlewareChain middlewareChain;
    // 会话存储
    private final SessionStore sessionStore;

    // 单次对话（模板方法）
    @Override
    public Mono<ChatResponse> chat(ChatRequest request) {
        // 子类可覆写具体步骤
    }

    // 流式对话（模板方法）
    @Override
    public Flux<ChatStreamChunk> stream(ChatRequest request) {
        // 子类可覆写具体步骤
    }

    // 获取会话句柄
    public Mono<Session> session(SessionId sessionId) {
        // 加载历史，返回可继续对话的 Session
    }
}
```

### 8.2 HarnessAgent（harness 层）

```java
// SDK 门面类，对外唯一入口
public class HarnessAgent {
    // 创建 Builder
    public static HarnessAgentBuilder builder() {
        return new HarnessAgentBuilder();
    }

    // Builder API 链式调用示例：
    // HarnessAgent.builder()
    //     .name("MyAgent")
    //     .model("openai", apiKey, "gpt-4o")
    //     .model("deepseek", apiKey, "deepseek-chat")
    //     .tool(new CalculatorTool())
    //     .toolPackage("com.example.myapp.tools")    // 包扫描
    //     .hook(new AuditHook())
    //     .tenant(tenantId)
    //     .sessionStore(new MysqlSessionStore(dataSource))
    //     .build()
}
```

## 9. 编码规范

- 所有类名：PascalCase，不使用缩写（LLM、HTTP、API、SDK 等通用缩写除外）
- 所有方法名：camelCase
- 所有常量：UPPER_SNAKE_CASE
- 所有包名：全小写，尽量单单词
- 不使用 `record` 关键字 — 使用传统 POJO 类 getter/setter
- 不使用 AgentBase、ReActAgent 等与 AgentScope 雷同的命名
- **所有公开 API 和关键实现逻辑必须有中文注释**
- 序列化统一使用 com.alibaba.fastjson2

## 10. 构建与运行

```
# 全部构建
./mvnw clean package -DskipTests

# 启动 bootstrap 模块
./mvnw -pl agent-bootstrap spring-boot:run

# 测试页面访问
http://localhost:8080
```
