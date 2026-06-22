# AgentScope Java 源码深度教学

> 从 `agent.call(msgs)` 一行代码出发，逐函数、逐类、逐设计决策，带你彻底理解 Agent 框架的每一行代码。

---

## 教学说明

本教程严格遵循**时间线教学法**：从 `agent.call(msgs)` 入口开始，顺着代码执行顺序逐步展开。每当遇到一个新概念、新类、新方法，立即原地深入展开全部知识体系——不跳跃、不遗漏、不敷衍。

每一篇都遵循三个原则：
1. **把技术具象化**——用生活场景比喻技术概念，让抽象变得可触摸
2. **探讨为什么**——不只讲"是什么"，更要讲"为什么这样设计""还有什么替代方案"
3. **倾尽所有**——每篇都按写书的深度来，不保留任何细节

---

## 第一卷：起航——从一行 call() 说起

| 编号 | 篇名 | 核心问题 |
|------|------|----------|
| 1 | Reactor 响应式编程——理解框架的"语言" | 为什么 Agent 框架必须异步？Mono/Flux 是什么？ |
| 2 | `Mono.using`——资源管理的"获取-使用-释放"模式 | 如何保证资源在任何情况下都能释放？ |
| 3 | Agent 接口体系——三层能力契约 | CallableAgent / StreamableAgent / ObservableAgent 各司何职？ |
| 4 | AgentBase 类——Agent 的"骨架" | 所有 Agent 共享哪些基础设施？ |
| 5 | Msg 消息体系——Agent 的语言 | 一条消息如何承载文本、思考、工具调用、工具结果？ |
| 6 | Memory 记忆体系——Agent 的"海马体" | 对话历史存在哪里？如何持久化到数据库？ |

## 第二卷：战前准备——acquireExecution 到 beforeAgentExecution

| 编号 | 篇名 | 核心问题 |
|------|------|----------|
| 7 | `acquireExecution()`——"上场前的安检" | CAS 并发守卫、中断复位、关闭检查做了什么？ |
| 8 | `beforeAgentExecution(msgs)`——RuntimeContext 绑定 | 每次调用的"身份卡"如何注入到 Hook 系统？ |
| 9 | RuntimeContext——"每次调用的身份卡" | sessionId、userId、属性存取、工具上下文如何传递？ |
| 10 | `call()` 方法的三个重载 | 普通调用 vs Class 结构化 vs JsonNode 结构化有何不同？ |

## 第三卷：Hook 系统——框架的"插件体系"

| 编号 | 篇名 | 核心问题 |
|------|------|----------|
| 11 | Hook 系统架构总览 | 单分派接口、优先级体系、静态钩子 vs 实例钩子 |
| 12 | HookEvent 密封类体系——十二种事件全景 | 12 种事件各在什么时机触发？哪些可修改、哪些只读？ |
| 13 | PreCallEvent 完整拆解 | 调用入口的第一个事件，Hook 能做什么？ |
| 14 | PostCallEvent 完整拆解 | 最终消息的"最后一瞥" |
| 15 | PreReasoningEvent 完整拆解 | 每轮推理前，Hook 如何操纵 LLM 输入？ |
| 16 | PostReasoningEvent 完整拆解 | 推理完成后，stop / gotoReasoning / 继续执行的决策点 |
| 17 | ReasoningChunkEvent 完整拆解 | 流式分块通知——只能看不能改 |
| 18 | PreActingEvent 完整拆解 | 工具执行前，Hook 如何修改工具参数？ |
| 19 | PostActingEvent 完整拆解 | 工具执行后，结果可以被 Hook 替换吗？ |
| 20 | ActingChunkEvent 完整拆解 | 工具执行中的中间分块如何暴露？ |
| 21 | PreSummaryEvent 完整拆解 | 超过 maxIters 后的总结阶段，Hook 能做什么？ |
| 22 | SummaryChunkEvent 与 PostSummaryEvent | 总结阶段的流式通知与后置处理 |
| 23 | ErrorEvent 完整拆解 | 异常发生时的只读通知 |

## 第四卷：PreCall——"战前会议"

| 编号 | 篇名 | 核心问题 |
|------|------|----------|
| 24 | `notifyPreCall(List<Msg>)`——完整流程拆解 | 快照→输入构建→Hook 链→冻结→尾部提取→守卫 |
| 25 | `seedSystemMsg()`——系统消息的"播种" | sysPrompt 如何变成系统消息？ |
| 26 | `consumeSystemMsgAfterPreCall(Msg)`——系统消息的"冻结" | 为什么要在 PreCall 后冻结系统消息？ |
| 27 | PreCall 阶段的 Hook 大展演 | 优先级 0 到 900 的 Hook 如何"层层加料"？ |

## 第五卷：系统中每个 Hook 的完整实现

| 编号 | 篇名 | 核心问题 |
|------|------|----------|
| 28 | GracefulShutdownHook——"关闭的哨兵"（优先级 0） | 系统关闭时如何优雅地通知 Agent？ |
| 29 | PendingToolRecoveryHook——"孤儿工具的收容所"（优先级 10） | 工具超时/中断后，如何自动恢复状态？ |
| 30 | GenericRAGHook——"自动检索的知识注入器"（优先级 50） | 知识库内容如何自动注入对话？ |
| 31 | RAG 知识库体系——企业知识增强全方案 | GENERIC vs AGENTIC 模式、多库聚合、扩展模块全览 |
| 32 | StaticLongTermMemoryHook——"长期记忆的自动管家"（优先级 50） | 跨会话记忆如何检索和记录？同步 vs 异步？ |
| 33 | LongTermMemory 体系——跨会话的知识积累 | Mem0、Bailian、ReMe 三种实现对比、企业向量数据库接入 |
| 34 | SkillHook——"技能目录的注册官"（优先级 85） | 技能提示如何注入系统消息？ |
| 35 | SkillBox——"技能的中央调度台" | SkillRegistry、SkillToolFactory、autoUpload 完整流程 |
| 36 | AgentSkill——技能文件格式与解析 | SKILL.md 格式、.skill/.zip 打包、MarkdownSkillParser |
| 37 | SkillToolFactory——`load_skill_through_path` 工具 | Agent 如何在运行时动态加载技能？ |
| 38 | AgentSkillPromptProvider——技能目录提示生成 | 系统提示中的 `<available_skills>` XML 如何构建？ |
| 39 | SkillRepository——技能的存储与发现 | FileSystem / Classpath / Git / MySQL / Nacos 五种仓库 |
| 40 | DynamicSkillHook——多仓库动态技能组合（优先级 85） | 如何实现按用户命名空间覆盖技能？ |
| 41 | SubagentsHook——子Agent的调度中心（优先级 80） | 80 行的 `## Subagents` 提示模板详解 |
| 42 | DynamicSubagentsHook——按用户动态子Agent解析（优先级 80） | 两层合并策略：workspace 基础 + 用户覆盖 |
| 43 | SubAgentTool——把 Agent 包装成工具 | 工具名生成、会话管理、事件转发 |
| 44 | SubAgent 的声明与发现体系 | SubagentDeclaration、AgentSpecLoader、WorkspaceMode |
| 45 | AgentSpawnTool——子Agent的生命周期管理 | agent_spawn / agent_send / agent_list 的完整实现 |
| 46 | TaskTool 与 TaskRepository——异步任务追踪 | 任务提交、轮询、取消、跨节点持久化 |
| 47 | RemoteSubagentStub——远程子Agent的占位符 | Agent Protocol HTTP 客户端 |
| 48 | SubagentEventBus——父子Agent的事件通道 | Reactor Context 隐式传参 |
| 49 | DefaultAgentManager——Agent 工厂调度器 | 竞态安全查找、原子快照替换 |
| 50 | WorkspaceContextHook——"工作空间的感知层"（优先级 900） | AGENTS.md / MEMORY.md / KNOWLEDGE.md 如何被读取注入？ |
| 51 | CompactionHook——"记忆压缩器"（优先级 10） | LLM 驱动的对话压缩如何工作？ |
| 52 | MemoryFlushHook——"记忆冲洗阀"（优先级 5） | 长期记忆冲洗 + 消息卸载 |
| 53 | MemoryMaintenanceHook——"记忆保洁员"（优先级 6） | 过期清理、LLM 整合、日志修剪（30 分钟节流） |
| 54 | ToolResultEvictionHook——"大结果的磁盘化"（优先级 50） | 工具结果超过阈值 → 写文件 + 替换为预览 |
| 55 | SandboxLifecycleHook——"沙箱的生命管家"（优先级 50） | AtomicReference 而非 ThreadLocal 的原因 |
| 56 | SessionPersistenceHook——"状态的自动存档"（优先级 900） | 每次调用后自动 saveTo session |
| 57 | AgentTraceHook——"Agent 的全息日志"（优先级 0） | 8 种事件的 INFO/DEBUG 日志 |
| 58 | JsonlTraceExporter——"事件的可审计记录"（优先级 900） | HookEvent → JSONL 文件、runId/turnId/stepId |
| 59 | TTSHook——"让 Agent 开口说话" | 实时 TTS vs 批处理 TTS、三重音频输出 |

## 第六卷：Tracer 追踪系统

| 编号 | 篇名 | 核心问题 |
|------|------|----------|
| 60 | Tracer 接口——可插拔的追踪层 | 如何包装 Agent/Model/Tool 调用的追踪？ |
| 61 | TracerRegistry——全局追踪注册表 | Reactor onEachOperator 钩子如何传播追踪上下文？ |
| 62 | TelemetryTracer——OpenTelemetry 集成 | Studio 扩展的 OpenTelemetry 兼容追踪 |

## 第七卷：进入 ReAct——doCall 的分流逻辑

| 编号 | 篇名 | 核心问题 |
|------|------|----------|
| 63 | `doCall(List<Msg>)`——ReActAgent 的入口 | 四个分支：无挂起/恢复/用户提供结果/异常 |
| 64 | `getPendingToolUseIds()`——挂起工具的识别算法 | 如何判断哪些工具调用还没有结果？ |
| 65 | `addToMemory(List<Msg>)`——消息写入记忆 | 为什么要把消息写入 Memory 而不是直接传给 LLM？ |
| 66 | `validateAndAddToolResults(List<Msg>, Set<String>)`——"工具结果的安检" | 重复 ID、无效 ID、部分结果 的校验逻辑 |

## 第八卷：reasoning——推理阶段完整拆解

| 编号 | 篇名 | 核心问题 |
|------|------|----------|
| 67 | `executeIteration(int)` → `reasoning(iter, false)` | 为什么用一个薄包装方法？ |
| 68 | ReasoningContext——"流式碎片的拼图师" | 文本/思维/工具调用三种分块如何累积与组装？ |
| 69 | `reasoning` 核心流程（一）——检查点 | 迭代上限检查、中断检查 |
| 70 | `reasoning` 核心流程（二）——PreReasoning Hook + 模型调用 | 系统消息注入、工具 Schema 传入、分块中断检查 |
| 71 | `reasoning` 核心流程（三）——分块处理 + 中断异常 | 分块累积、Hook 通知、中断时 SAVE vs DISCARD |
| 72 | `reasoning` 核心流程（四）——PostReasoning + 决策 | stop / gotoReasoning / isFinished / acting 四路分流 |
| 73 | `isFinished(Msg)`——"任务完成的判断" | 为什么不是判断"有工具调用"而是判断"无工具调用"？ |
| 74 | `notifyReasoningChunk(Msg, ReasoningContext)`——分块 Hook 通知 | 增量块 vs 累积全量、三种 ContentBlock 的处理 |
| 75 | `buildGenerateOptions()`——生成参数构建 | 用户配置 + modelExecutionConfig 的合并策略 |

## 第九卷：Model 体系——LLM 调用的完整桥接

| 编号 | 篇名 | 核心问题 |
|------|------|----------|
| 76 | Model 接口——模型的统一契约 | 为什么只定义一个 `stream()` 方法？ |
| 77 | ChatModelBase——所有模型的公共基类 | Tracer 如何包装模型调用？ |
| 78 | GenerateOptions——生成参数的全景控制 | temperature、thinking、toolChoice、apiKey 等全部参数 |
| 79 | ExecutionConfig——执行策略配置 | timeout、retry、backoff、jitter 的默认值与合并 |
| 80 | Formatter 体系——Java 消息→提供商请求的翻译官 | 为什么用 Formatter 模式而不是每个模型硬编码？ |
| 81 | OpenAI Formatter 深度拆解 | 消息转换、SSE 响应解析、多 Agent 格式化 |
| 82 | Anthropic Formatter 深度拆解 | 系统消息的特殊处理、Tool 格式转换 |
| 83 | DashScope Formatter 深度拆解 | EndpointType 路由、Tool 格式转换 |
| 84 | Gemini Formatter 深度拆解 | thoughtSignature 元数据处理 |
| 85 | Ollama Formatter 深度拆解 | 本地模型的特殊处理 |
| 86 | MultiAgentFormatter——多 Agent 对话格式化 | `BYPASS_MULTIAGENT_HISTORY_MERGE` 的用途 |
| 87 | ModelRegistry——模型的自动发现与注册 | 7 种内置模式的正则匹配、缓存机制 |
| 88 | 各 Model 实现的流式调用细节 | OpenAI / Anthropic / DashScope / Gemini / Ollama 的 SSE 处理 |
| 89 | Model 的网络层——HttpTransport 与 ProxyConfig | 连接池、超时、HTTP/SOCKS 代理 |

## 第十卷：acting——工具执行阶段完整拆解

| 编号 | 篇名 | 核心问题 |
|------|------|----------|
| 90 | `acting(int iter)`——执行阶段的完整流程 | 提取工具→Hook→执行→分流→决策 |
| 91 | `extractPendingToolCalls()`——"只执行该执行的" | 为什么要有"pending"概念？去重逻辑 |
| 92 | `notifyPreActingHooks(List<ToolUseBlock>)`——逐个工具的前置 Hook | 为什么每个工具独立触发 PreActingEvent？ |
| 93 | `executeToolCalls(List<ToolUseBlock>)`——批量工具执行 | 配对结果、异常转错误结果、InterruptedException 保留 |
| 94 | `notifyPostActingHook(Map.Entry)`——单个工具结果的后置 Hook | ToolResultBlock → TOOL 角色 Msg → Memory 写入 |

## 第十一卷：Toolkit——工具的"军火库"

| 编号 | 篇名 | 核心问题 |
|------|------|----------|
| 95 | Toolkit 外观模式——六大组件的协调者 | 为什么用外观模式封装 6 个内部组件？ |
| 96 | ToolRegistry——工具的"花名册" | 并发注册、原子移除（CAS 防误删） |
| 97 | ToolGroupManager——"工具的开关面板" | 工具组 CRUD、动态激活/停用、权限控制 |
| 98 | MetaToolFactory——`reset_equipped_tools` 元工具 | Agent 如何在运行时切换工具集？ |
| 99 | `@Tool` 注解——声明一个函数是工具 | 注解字段、`@ToolParam`、反射扫描与注册 |
| 100 | ToolSchemaGenerator——从 Java 方法签名到 JSON Schema | Jackson 生成器、类型映射、嵌套对象处理 |
| 101 | ToolValidator——工具参数的 Schema 校验 | JSON Schema 校验失败时的错误报告 |
| 102 | ToolMethodInvoker——Java 方法反射调用 | 参数注入机制（Agent、ToolEmitter、POJO 反序列化） |
| 103 | ToolResultConverter——方法返回值→ToolResultBlock | 基本类型/对象/null 的转换策略 |
| 104 | ToolEmitter——工具执行中的流式传声筒 | emit/DefaultToolEmitter/NoOpToolEmitter |
| 105 | ToolCallParam——工具调用的完整参数包 | ToolUseBlock + input + Agent + Context + Emitter |
| 106 | ToolSuspendException——"工具的暂停按钮" | 挂起-恢复循环：人工审批场景的完整时序 |
| 107 | ToolResultMessageBuilder——工具结果→完整 Msg | TOOL 角色消息的构建规范 |

## 第十二卷：ToolExecutor——工具执行的"装甲层"

| 编号 | 篇名 | 核心问题 |
|------|------|----------|
| 108 | ToolExecutor 概览——"四层装甲" | 调度层、超时层、重试层、关闭守卫层 |
| 109 | `executeAll()`——批量工具的入口 | 并行 `mergeSequential` vs 顺序 `concat` |
| 110 | `executeWithInfrastructure()`——四层装甲组装 | 构建参数→核心执行→逐层包裹 |
| 111 | `applyScheduling()`——线程调度层 | boundedElastic vs 自定义 ExecutorService |
| 112 | `applyTimeout()`——超时控制层 | Duration 超时 + 工具名日志 |
| 113 | `applyRetry()`——重试策略层 | 指数退避 + 50% 抖动 + 可配置重试条件 |
| 114 | `applyShutdownGuard()`——关闭守卫层 | `Mono.firstWithSignal` 竞争机制 |
| 115 | `executeCore()`——核心执行逻辑（七步工序） | 查找→激活→校验→上下文→参数→调用→异常转换 |
| 116 | chunkCallback——内部与用户的"双回调" | 各自独立、异常隔离、合并链路 |

## 第十三卷：MCP——Model Context Protocol 集成

| 编号 | 篇名 | 核心问题 |
|------|------|----------|
| 117 | MCP 协议概述——工具标准化的通用语言 | 为什么需要 MCP？三大传输方式对比 |
| 118 | McpClientWrapper 抽象体系 | Sync vs Async 包装器的实现差异 |
| 119 | McpClientBuilder——MCP 客户端的流式构建器 | Stdio / SSE / Streamable HTTP 三种传输配置 |
| 120 | McpTool——MCP 工具的 AgentTool 包装 | 预设参数合并、结果转换 |
| 121 | McpContentConverter——MCP 内容的转换器 | TextContent→TextBlock / ImageContent→ImageBlock |
| 122 | McpClientManager——MCP 客户端生命周期管家 | 注册、初始化、工具过滤、移除 |

## 第十四卷：PostCall——最终消息的 Hook 与广播

| 编号 | 篇名 | 核心问题 |
|------|------|----------|
| 123 | `notifyPostCall(Msg)`——"鸣金收兵" | null 检查、Hook 链、广播 |
| 124 | MsgHub 与 observe 模式——Agent 间的"传话筒" | 订阅关系管理、广播机制、observe vs call |
| 125 | `releaseExecution(AgentBase)`——"打扫战场" | afterAgentExecution、释放锁、注销请求 |

## 第十五卷：边界场景——总结、中断、错误恢复与关闭

| 编号 | 篇名 | 核心问题 |
|------|------|----------|
| 126 | `summarizing()`——"超限的最后挣扎" | 清理挂起工具→准备消息→流式总结→兜底错误 |
| 127 | `buildSuspendedMsg(List<Map.Entry>)`——挂起消息构建 | TOOL_SUSPENDED 消息的内容结构 |
| 128 | 中断机制全解析 | 标志位模型、检查点位置、响应式传播路径 |
| 129 | `createErrorHandler(Msg...)`——"错误的审判官" | InterruptedException vs 普通异常的分流 |
| 130 | `notifyError(Throwable)`——错误事件的 Hook 通知 | ErrorEvent 的 Fire-and-forget |
| 131 | `handleInterrupt(InterruptContext, Msg...)`——恢复策略 | 系统中断抛异常、用户中断返回恢复消息 |
| 132 | GracefulShutdownManager 全景（一）——状态机与注册 | RUNNING→SHUTTING_DOWN→TERMINATED、活跃请求追踪 |
| 133 | GracefulShutdownManager 全景（二）——关闭流程 | performGracefulShutdown、监控线程、JVM 钩子 |
| 134 | GracefulShutdownConfig 与 PartialReasoningPolicy | SAVE vs DISCARD 策略的场景选择 |

## 第十六卷：Builder 模式——Agent 的装配工厂

| 编号 | 篇名 | 核心问题 |
|------|------|----------|
| 135 | ReActAgent.Builder——定制 Agent 的完整菜单 | 20+ 个配置项的 Builder 模式设计 |
| 136 | `build()` 方法的完整流程 | 9 步构造：copy→注册Hook工具→Meta工具→各子系统装配→构造 |
| 137 | Toolkit 深拷贝——Agent 间工具隔离 | copy 什么、不 copy 什么、为什么？ |
| 138 | `configureLongTermMemory`——长期记忆自动装配 | 按 AGENT_CONTROL/STATIC_CONTROL/BOTH 三种模式配置 |
| 139 | `configureRAG`——知识库自动装配 | 多库聚合、GENERIC/AGENTIC 模式分发 |
| 140 | `configurePlan`——PlanNotebook 自动装配 | 注册计划工具 + 匿名计划提示 Hook |
| 141 | `configureSkillBox`——SkillBox 自动装配 | 绑定 Toolkit、注册 load 工具、上传文件、添加 Hook |

## 第十七卷：PlanNotebook——计划系统

| 编号 | 篇名 | 核心问题 |
|------|------|----------|
| 142 | PlanNotebook——计划的中央调度台 | Plan→Hint 策略、历史存储、确认机制 |
| 143 | Plan 模型——计划的数据结构 | Plan/SubTask/PlanState/SubTaskState |
| 144 | PlanNotebook 的十个工具方法详解 | create_plan 到 recover_historical_plan 全部拆解 |
| 145 | DefaultPlanToHint——计划提示的生成策略 | 五种场景的不同提示、核心规则文本 |
| 146 | PlanStorage——计划的历史存档 | InMemoryPlanStorage 与企业扩展方向 |

## 第十八卷：StructuredOutputCapableAgent——结构化输出

| 编号 | 篇名 | 核心问题 |
|------|------|----------|
| 147 | StructuredOutputCapableAgent 概览 | `generate_response` 工具 + StructuredOutputHook 的配合 |
| 148 | StructuredOutputHook——结构化输出的强制执行者（优先级 50） | 强制 tool_choice、最多 3 次重试、完成后压缩 Memory |

## 第十九卷：Event 流式传输

| 编号 | 篇名 | 核心问题 |
|------|------|----------|
| 149 | `stream()`——如何将 `call()` 转换为 `Flux<Event>` | Flux.create + StreamingHook + SubagentEventBus |
| 150 | StreamingHook——Hook 事件→Event 的翻译官 | 六种 HookEvent 到六种 Event 的映射 |
| 151 | Event 体系——EventType / Event / EventSource | 流式事件的数据结构、子 Agent 层级追踪 |
| 152 | StreamOptions——流式输出的精细控制 | 事件类型过滤、增量/全量、分块开关 |

## 第二十卷：State——会话持久化

| 编号 | 篇名 | 核心问题 |
|------|------|----------|
| 153 | State 与 StateModule——可持久化的标记 | Record 状态对象 + saveTo/loadFrom 契约 |
| 154 | Session 接口——状态存储的抽象 | save/get/delete/exists/listSessionKeys 方法详解 |
| 155 | SessionKey——会话的身份标识 | SimpleSessionKey 与自定义 Key 策略 |
| 156 | InMemorySession——开发环境的内存存储 | ConcurrentHashMap + SessionData 结构 |
| 157 | JsonSession——文件系统的 JSON 存储 | 目录结构、JSONL 增量持久化、哈希变更检测 |
| 158 | MysqlSession——MySQL 数据库存储（企业扩展） | 表结构、UPSERT、事务、SQL 注入防护 |
| 159 | RedisSession——Redis 存储（企业扩展） | Jedis/Lettuce/Redisson 四后端适配器 |
| 160 | JdbcStore——JDBC 键值存储（企业扩展） | CAS 并发控制、四种数据库方言 |
| 161 | RedisStore——Redis 键值存储（企业扩展） | Lua 脚本原子操作、Sorted Set 检索 |
| 162 | StatePersistence——可配置的状态管理范围 | memoryManaged/toolkitManaged/planNotebookManaged |
| 163 | SessionManager——状态管理的流式 API | 构建器模式的编排层 |

## 第二十一卷：设计哲学——"为什么这么设计"

| 编号 | 篇名 | 核心问题 |
|------|------|----------|
| 164 | 框架的十四大设计决策 | Memory 不在 AgentBase、call() 是 final、Hook 非继承... |

---

## 阅读建议

1. **按顺序阅读**：教程严格遵循代码执行时间线，跳读会导致知识断层
2. **对照源码**：建议克隆 [agentscope-java](https://github.com/agentscope-ai/agentscope-java) 仓库，边看文章边看代码
3. **动手实践**：每卷结束后尝试写一个简单的 Agent，验证你的理解
4. **企业读者**：标注"企业扩展"的篇章涵盖了 MySQL、Redis、多租户等生产环境方案

## 适合读者

- 传统 Java CRUD 程序员转型 Agent 开发
- 希望深入理解 AgentScope Java 框架源码的开发者
- 需要构建企业级 Agent 系统的架构师
- 对 Agent 框架设计哲学感兴趣的研究者
