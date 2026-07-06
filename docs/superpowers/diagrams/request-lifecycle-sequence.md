# ReAct Agent 请求生命周期时序图

## 无工具调用（单轮对话）

```mermaid
sequenceDiagram
    participant Client
    participant ReActAgent
    participant RequestPipeline
    participant SessionGate
    participant LoopExecutor
    participant LoopDecisionEngine
    participant ModelCallPipeline
    participant ChatModel
    participant HookDispatcher
    participant SessionPersistenceHook
    participant AgentStateStore

    Client->>ReActAgent: stream(messages)
    ReActAgent->>RequestPipeline: executeStream(messages, ctx)

    rect rgb(240, 248, 255)
        Note over RequestPipeline: 预处理管线
        RequestPipeline->>RequestPipeline: resolveContext(reactor ctx)
        RequestPipeline->>RequestPipeline: aroundCall (AroundHook 洋葱)
        RequestPipeline->>AgentStateStore: findById + loadLatestCheckpoint
        AgentStateStore-->>RequestPipeline: Session + Checkpoint
        RequestPipeline->>RequestPipeline: injectSystemMessage
        RequestPipeline->>RequestPipeline: LoopContextFactory.create()
    end

    RequestPipeline->>SessionGate: enqueueStream(sessionId, work)
    SessionGate->>LoopExecutor: runStream(loopCtx)

    rect rgb(255, 250, 240)
        Note over LoopExecutor: ReAct 状态机循环

        LoopExecutor->>LoopDecisionEngine: evaluate(Guard, ctx)
        LoopDecisionEngine-->>LoopExecutor: Continue(Reason)

        LoopExecutor->>LoopExecutor: executePhase(Reason)

        rect rgb(245, 255, 245)
            Note over LoopExecutor: Reason 阶段
            LoopExecutor->>ModelCallPipeline: executeStream(ctx)
            ModelCallPipeline->>HookDispatcher: PRE_REASONING
            ModelCallPipeline->>HookDispatcher: PRE_MODEL_CALL
            ModelCallPipeline->>ChatModel: streamWithTools(messages, schemas, opts)
            ChatModel-->>ModelCallPipeline: text chunks...
            ModelCallPipeline->>HookDispatcher: POST_MODEL_CALL
            ModelCallPipeline->>HookDispatcher: POST_REASONING
            ModelCallPipeline-->>LoopExecutor: [chunks]
            LoopExecutor->>LoopExecutor: setLastResponse + addTokens + addMessage
        end

        LoopExecutor->>LoopDecisionEngine: evaluate(Reason, ctx)
        Note over LoopDecisionEngine: lastResponse 无 ToolUseBlock<br/>→ markComplete()<br/>→ Continue(Observe)
        LoopDecisionEngine-->>LoopExecutor: Continue(Observe)

        LoopExecutor->>LoopExecutor: executePhase(Observe)

        rect rgb(255, 245, 245)
            Note over LoopExecutor: Observe 阶段（持久化）
            LoopExecutor->>LoopExecutor: iteration++
            LoopExecutor->>HookDispatcher: dispatch AFTER_ITERATION
            HookDispatcher->>SessionPersistenceHook: onEvent(AFTER_ITERATION)
            SessionPersistenceHook->>AgentStateStore: addTurn(sessionId, turn)
            SessionPersistenceHook->>AgentStateStore: saveCheckpoint(state)
        end

        LoopExecutor->>LoopDecisionEngine: evaluate(Observe, ctx)
        LoopDecisionEngine-->>LoopExecutor: Continue(Guard)

        LoopExecutor->>LoopDecisionEngine: evaluate(Guard, ctx)
        Note over LoopDecisionEngine: ctx.isComplete() == true<br/>→ Decision.stop()
        LoopDecisionEngine-->>LoopExecutor: Stop
    end

    LoopExecutor-->>SessionGate: Flux<ChatStreamChunk>
    SessionGate-->>RequestPipeline: Flux<ChatStreamChunk>
    RequestPipeline-->>ReActAgent: Flux<ChatStreamChunk>
    ReActAgent-->>Client: SSE stream
```

## 有工具调用

```mermaid
sequenceDiagram
    participant LoopExecutor
    participant LoopDecisionEngine
    participant ModelCallPipeline
    participant ChatModel
    participant ToolCallOrchestrator
    participant ToolExecutor
    participant HookDispatcher
    participant SessionPersistenceHook

    Note over LoopExecutor: ...前置 Guard→Reason 同无工具场景...

    LoopExecutor->>ModelCallPipeline: executeStream(ctx)
    ChatModel-->>ModelCallPipeline: tool_use chunks...
    ModelCallPipeline-->>LoopExecutor: [chunks]

    LoopExecutor->>LoopExecutor: setLastResponse + addTokens<br/>+ addMessage (含 tool_use blocks)

    LoopExecutor->>LoopDecisionEngine: evaluate(Reason, ctx)
    Note over LoopDecisionEngine: lastResponse 有 ToolUseBlock<br/>→ Continue(Act)
    LoopDecisionEngine-->>LoopExecutor: Continue(Act[tools])

    LoopExecutor->>LoopExecutor: executePhase(Act)

    rect rgb(245, 245, 255)
        Note over LoopExecutor: Act 阶段
        LoopExecutor->>LoopExecutor: metrics.recordIteration(+1)
        loop 每个工具
            LoopExecutor->>ToolCallOrchestrator: execute(toolCall, ctx)
            ToolCallOrchestrator->>ToolExecutor: execute(callParam)
            ToolExecutor-->>ToolCallOrchestrator: ToolResult
            ToolCallOrchestrator-->>LoopExecutor: ToolResult
        end
        LoopExecutor->>LoopExecutor: appendToolResults (tool_result msgs)
        Note over LoopExecutor: backoff delay
    end

    LoopExecutor->>LoopDecisionEngine: evaluate(Act, ctx)
    LoopDecisionEngine-->>LoopExecutor: Continue(Observe)

    LoopExecutor->>LoopExecutor: executePhase(Observe)

    rect rgb(255, 245, 245)
        Note over LoopExecutor: Observe 阶段
        LoopExecutor->>LoopExecutor: iteration++
        LoopExecutor->>HookDispatcher: dispatch AFTER_ITERATION
        HookDispatcher->>SessionPersistenceHook: onEvent
        SessionPersistenceHook->>SessionPersistenceHook: persistTurn + saveCheckpoint
    end

    LoopExecutor->>LoopDecisionEngine: evaluate(Observe, ctx)
    LoopDecisionEngine-->>LoopExecutor: Continue(Guard)

    Note over LoopExecutor: ...后续 Guard→Reason(无工具)→Observe→Guard(complete)→Stop...
```

## 介入中断与恢复

```mermaid
sequenceDiagram
    participant LoopExecutor
    participant LoopDecisionEngine
    participant ToolCallOrchestrator
    participant ToolExecutor
    participant InterventionStore
    participant HookDispatcher
    participant Client

    Note over LoopExecutor: ...Reason→Act 执行工具...

    ToolCallOrchestrator->>ToolExecutor: execute(callParam)
    ToolExecutor-->>ToolCallOrchestrator: HumanInterventionException

    rect rgb(255, 240, 240)
        Note over LoopExecutor: 介入中断处理
        LoopExecutor->>LoopExecutor: handleIntervention(hie, ctx)
        LoopExecutor->>InterventionStore: create(InterventionRequest)
        InterventionStore-->>LoopExecutor: interventionId
        LoopExecutor->>LoopExecutor: ctx.interrupt() + setInterventionId
        LoopExecutor-->>Client: intervention chunk (type=intervention, finish=interrupted)
    end

    Note over LoopExecutor: executePhase 继续 → Act评估→Observe<br/>executeObserve: iteration++ + dispatchAfterIteration (持久化介入状态)<br/>→ Guard评估→Reason → isInterrupted检查 → Flux.empty()

    Note over Client,InterventionStore: ...用户审批后重新请求...

    Client->>LoopExecutor: 新请求 → runStream
    LoopExecutor->>InterventionStore: getById(interventionId)
    InterventionStore-->>LoopExecutor: APPROVED
    LoopExecutor->>LoopExecutor: resumeApprovedTool

    rect rgb(240, 255, 240)
        Note over LoopExecutor: 介入恢复
        LoopExecutor->>ToolCallOrchestrator: executeDirect (原参数)
        ToolCallOrchestrator-->>LoopExecutor: ToolResult
        LoopExecutor->>LoopExecutor: appendSingleToolResult
        LoopExecutor->>LoopExecutor: executePhase(Observe)
        Note over LoopExecutor: iteration++ + 持久化 → Guard → Reason → ...
    end
```

## 四阶段状态机总览

```mermaid
stateDiagram-v2
    [*] --> GUARD

    GUARD --> GUARD: complete? → Stop
    GUARD --> REASON: Continue
    GUARD --> REASON: maxIter? → 注入总结提示词

    REASON --> REASON: 模型调用 + 收集回复
    REASON --> ACT: 有 ToolUseBlock
    REASON --> OBSERVE: 无 ToolUseBlock → markComplete()

    ACT --> ACT: 执行工具 + 收集结果
    ACT --> OBSERVE: Continue

    OBSERVE --> OBSERVE: iteration++ + dispatchAfterIteration
    OBSERVE --> GUARD: Continue

    note right of GUARD
        LoopDecisionEngine.evaluate(Guard)
        - 检查 isComplete() → Stop
        - 检查 maxIterations → 注入提示词
        - 否则 → Continue(Reason)
    end note

    note right of REASON
        LoopDecisionEngine.evaluate(Reason)
        - 读 lastResponse.getToolUseBlocks()
        - 有工具 → Continue(Act)
        - 无工具 → markComplete → Continue(Observe)
    end note

    note right of OBSERVE
        LoopExecutor.executeObserve
        - iteration++
        - dispatchAfterIteration
        - AFTER_ITERATION Hook
        - SessionPersistenceHook
    end note
```

## 组件职责一览

| 组件 | 职责 | 依赖 |
|------|------|------|
| **ReActAgent** | 薄门面，组装各子系统 | RequestPipeline |
| **RequestPipeline** | 预处理：解析上下文、加载会话、注入系统消息、AroundHook | LoopExecutor, AgentStateStore, SessionGate |
| **SessionGate** | 会话级 FIFO 串行化 | — |
| **LoopExecutor** | ReAct 循环执行器，executePhase 路由中枢 | LoopDecisionEngine, ModelCallPipeline, ToolCallOrchestrator |
| **LoopDecisionEngine** | 纯同步状态机，4 阶段评估，唯一路由裁判 | — |
| **ModelCallPipeline** | 模型调用 + PRE/POST Hook 管线 | ChatModel, HookDispatcher |
| **ToolCallOrchestrator** | 工具执行编排 + 介入审批 | ToolExecutor, HookDispatcher |
| **HookDispatcher** | Hook 事件分发 | HookChain |
| **SessionPersistenceHook** | AFTER_ITERATION 监听，持久化 turn + checkpoint | AgentStateStore |
