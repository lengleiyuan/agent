# 工具审批流程

## 概述

当 Agent 执行高风险操作（大额转账、文件删除、生产环境变更等）时，需要在执行前暂停并等待人工审批。HarnessAgent 提供了完整的"暂停 → 审批 → 恢复"机制，无需业务方自行实现阻塞等待逻辑。

## 核心概念

| 概念 | 说明 |
|---|---|
| **`requiresApproval()`** | 工具接口上的方法，返回 `true` 表示调用此工具前必须人工审批 |
| **`ToolSuspendException`** | 工具内部抛出的异常，触发审批流程。携带决策问题（question）和可选项（options） |
| **`InterruptEvent`** | 审批事件，携带 `interruptId`、`reason`、`toolName`。Hook 可监听此事件做自定义处理 |
| **`agent.interrupt(feedback)`** | 外部注入审批结果的方法。调用后循环从断点恢复继续执行 |

## 完整链路

```
┌─────────────────────────────────────────────────────────┐
│                    业务方接入层                            │
│                                                          │
│  ① 工具声明 requiresApproval() = true                      │
│     或 工具内部 throw new ToolSuspendException(...)         │
│                                                          │
│  ② [可选] 注册 ON_INTERRUPT Hook                          │
│     监听审批事件，做审计日志 / 通知推送 / 否决操作             │
│                                                          │
│  ③ [外部] 调用 agent.interrupt(feedback) 注入审批结果       │
└─────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────┐
│                    框架自动处理                            │
│                                                          │
│  ToolExecutor  →  检测 requiresApproval / ToolSuspendException
│       │                                                  │
│       ▼                                                  │
│  ReActLoop  →  分发 ON_INTERRUPT 事件给 Hook 链            │
│       │                                                  │
│       ├── Hook 返回 abort   → 返回 "操作被拒绝"              │
│       │                      循环继续，LLM 告知用户          │
│       │                                                  │
│       └── Hook 不否决       → ctx.interrupt()  暂停循环      │
│                               返回 "等待审批"               │
│                               控制权交还调用方               │
│                                                          │
│  外部调用 agent.interrupt(feedback)                        │
│       │                                                  │
│       ▼                                                  │
│  循环恢复 → LLM 看到审批结果 → 重试工具 → 执行成功 → 返回      │
└─────────────────────────────────────────────────────────┘
```

## 接入方式

### 方式一：静态声明（推荐）

工具覆写 `requiresApproval()`，在执行前触发审批。适合规则明确、条件简单的场景。

```java
public class TransferTool implements Tool {

    private static final long MAX_AUTO_AMOUNT = 10000;

    @Override
    public String getName() { return "transfer"; }

    @Override
    public String getDescription() { return "转账工具"; }

    @Override
    public boolean requiresApproval() {
        // 仅声明需要审批，不做条件判断
        return true;
    }

    @Override
    public ToolSchema getParameters() {
        // 参数定义：金额 amount，收款人 target
        ...
    }

    @Override
    public Mono<ToolResult> execute(ToolCallContext params) {
        long amount = params.getNumber("amount").longValue();
        String target = params.getString("target");
        // 执行转账逻辑
        doTransfer(amount, target);
        return Mono.just(ToolResult.success("已向 " + target + " 转账 " + amount + " 元"));
    }
}
```

### 方式二：动态决策（条件判断）

工具内部根据参数动态决定是否需要审批。适合需要运行时判断的场景。

```java
public class TransferTool implements Tool {

    private static final long MAX_AUTO_AMOUNT = 10000;

    @Override
    public String getName() { return "transfer"; }

    @Override
    public String getDescription() { return "转账工具"; }

    @Override
    public boolean requiresApproval() {
        return false; // 默认不需要，在 execute 内部动态判断
    }

    @Override
    public Mono<ToolResult> execute(ToolCallContext params) {
        long amount = params.getNumber("amount").longValue();
        String target = params.getString("target");

        if (amount > MAX_AUTO_AMOUNT) {
            throw new ToolSuspendException("transfer",
                "转账金额 " + amount + " 元超过 " + MAX_AUTO_AMOUNT + " 元上限，是否继续？",
                new String[]{"批准", "拒绝"});
        }

        doTransfer(amount, target);
        return Mono.just(ToolResult.success("已向 " + target + " 转账 " + amount + " 元"));
    }
}
```

## Hook 扩展

### 审计日志 Hook

记录每次审批事件的完整信息。

```java
public class ApprovalAuditHook implements Hook {

    @Override
    public String getName() { return "approval-audit"; }

    @Override
    public Set<HookEventType> getSubscribedEventTypes() {
        return Set.of(HookEventType.ON_INTERRUPT);
    }

    @Override
    public int getPriority() { return 100; } // 最后执行，不影响决策

    @Override
    public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
        if (event instanceof InterruptEvent ie) {
            log.info("[审批审计] session={}, interruptId={}, tool={}, reason={}, resolved={}",
                context.getSessionId(),
                ie.getInterruptId(),
                ie.getToolName(),
                ie.getReason(),
                ie.isResolved());
        }
        return Mono.just(HookResult.continue_());
    }
}
```

### 否决规则 Hook

特定条件下直接否决操作。

```java
public class ApprovalDenyHook implements Hook {

    private final Set<String> denyListTools = Set.of("delete_file", "drop_table");

    @Override
    public String getName() { return "approval-deny"; }

    @Override
    public Set<HookEventType> getSubscribedEventTypes() {
        return Set.of(HookEventType.ON_INTERRUPT);
    }

    @Override
    public int getPriority() { return 5; } // 高优先级，在审批 Hook 之前执行

    @Override
    public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
        if (event instanceof InterruptEvent ie) {
            if (denyListTools.contains(ie.getToolName())) {
                return Mono.just(HookResult.abort("工具 [" + ie.getToolName() + "] 已被系统禁止"));
            }
        }
        return Mono.just(HookResult.continue_());
    }
}
```

注册 Hook：

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("finance-agent")
    .model(new DeepSeekChatModel("sk-xxx", "deepseek-chat"))
    .tool(new TransferTool())
    .hook(new ApprovalAuditHook())   // 审计
    .hook(new ApprovalDenyHook())    // 否决规则
    .build();
```

## API 调用流程

### 非流式

```
┌──────────┐     ┌──────────┐     ┌──────────┐     ┌──────────┐
│  客户端    │     │ Agent API │     │ ReActLoop │     │  Tool    │
└────┬─────┘     └────┬─────┘     └────┬─────┘     └────┬─────┘
     │                 │               │                 │
     │ POST /chat      │               │                 │
     │ {"message":"转账2万"}│             │                 │
     │────────────────▶│               │                 │
     │                 │  execute()    │                 │
     │                 │──────────────▶│                 │
     │                 │               │  execute()      │
     │                 │               │────────────────▶│
     │                 │               │                 │
     │                 │               │ ToolSuspendExc  │
     │                 │               │◀────────────────│
     │                 │               │                 │
     │                 │               │ ctx.interrupt() │
     │                 │               │ (循环暂停)       │
     │                 │               │                 │
     │                 │  返回 interrupted 响应            │
     │                 │◀──────────────│                 │
     │                 │               │                 │
     │  { "status": "interrupted",    │                 │
     │    "reason": "转账金额超限" }    │                 │
     │◀────────────────│               │                 │
     │                 │               │                 │
     │  ═══════ 用户看到审批请求，做出决策 ═══════             │
     │                 │               │                 │
     │ POST /chat      │               │                 │
     │ {"message":"批准执行"}│             │                 │
     │────────────────▶│               │                 │
     │                 │  execute()    │                 │
     │                 │──────────────▶│                 │
     │                 │               │                 │
     │                 │  handlerExternalInterrupt()      │
     │                 │  注入 "批准执行" 消息                │
     │                 │  清除中断标志                       │
     │                 │  恢复循环    │                 │
     │                 │               │                 │
     │                 │               │  execute()      │
     │                 │               │────────────────▶│
     │                 │               │  成功: "已转账"   │
     │                 │               │◀────────────────│
     │                 │               │                 │
     │                 │  返回最终结果   │                 │
     │                 │◀──────────────│                 │
     │                 │               │                 │
     │  { "content": "已向xxx转账2万元" }│                 │
     │◀────────────────│               │                 │
```

### 流式（SSE）

流式模式下流程相同，但中断状态通过 SSE 事件流返回：

```
event: text
data: {"delta": "", "finishReason": "interrupted"}

// 客户端检测到 finishReason=interrupted，提示用户
```

审批后重新发起流式请求，继续接收后续事件。

## 审批恢复机制

### 恢复条件

调用 `agent.interrupt(feedback)` 后，下一次 `execute()` 调用会：

1. 检测 `LoopContext.isInterrupted() = true`
2. 检测 `LoopContext.getFeedbackMsg() != null`（feedback 已注入）
3. 将 feedback 消息追加到对话历史
4. 清除中断标志
5. 恢复 ReAct 循环，LLM 看到完整历史后可重新调用工具

### 恢复后的对话上下文

LLM 在恢复后会看到如下消息序列：

```
[user]      "转账 2 万元给张三"
[assistant]  tool_use: transfer(amount=20000, target="张三")
[tool]      "等待审批: 转账金额超过1万，是否继续？"
[user]      "批准执行"                                  ← 外部注入
[assistant]  tool_use: transfer(amount=20000, target="张三")  ← LLM 重试
[tool]      "APPROVED: 已向张三转账 20000 元"
[assistant]  "操作已批准并执行成功"
```

**流程完整，对 LLM 透明** —— 它看到审批请求和审批结果后，会自主决定重新调用工具。

## 注意事项

1. **审批消息注入时机**: 必须在 `execute()` 返回中断响应之后、下一次 `execute()` 调用之前注入 feedback
2. **Hook 优先级**: 否决类 Hook 优先级应设为 1-10（最先执行），审计类设为 100+（最后执行）
3. **并发安全**: `interrupt()` 使用 `volatile` 标志，外部线程安全
4. **状态存储**: 启用 `SessionPersistenceHook` 可将中断状态持久化到数据库，支持崩溃恢复和跨进程审批
5. **超时处理**: 建议业务方设置审批超时，超时后自动调用 `agent.interrupt(UserMessage.of("审批超时，自动拒绝"))`

## 测试参考

完整测试用例见 `ReActLoopTest.java`：

| 测试方法 | 覆盖场景 |
|---|---|
| `testApprovalFlowPauseAndResume` | 完整链路：暂停 → 审批 → 恢复 → 重试成功，验证 7 步消息序列 |
| `testApprovalFlowAbortByHook` | Hook 否决：确认循环不暂停、工具返回"操作被拒绝" |
| `testApprovalFlowWithoutFeedbackStaysInterrupted` | 无反馈：确认循环保持中断，不会自动恢复 |
