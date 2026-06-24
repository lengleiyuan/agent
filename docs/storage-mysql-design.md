# MySQL 存储层设计

## 需要实现的接口 → 表映射

| 接口（SDK已定义） | 对应表 | 说明 |
|-------------------|--------|------|
| `AgentStateStore` | `agent_sessions` / `agent_turns` / `agent_checkpoints` | 会话+轮次+检查点 |
| `Memory` | `agent_memories` | 长期记忆 |
| —（Hook 层直接写） | `agent_audit_logs` | 审计日志 |
| —（Hook 层直接写） | `agent_token_usage` | Token用量 |
| —（启动时加载到 ToolRegistry） | `agent_tenant_tools` | 租户工具配置 |
| —（ChatModel 层查） | `agent_credentials` | 模型凭证 |

---

## 一、DDL

### agent_sessions

```sql
CREATE TABLE agent_sessions (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id      VARCHAR(64)  NOT NULL UNIQUE,
    tenant_id       VARCHAR(64)  NOT NULL,
    user_id         VARCHAR(64)  DEFAULT NULL,
    agent_name      VARCHAR(128) NOT NULL,
    state           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    turn_count      INT          NOT NULL DEFAULT 0,
    total_tokens    BIGINT       NOT NULL DEFAULT 0,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_tenant (tenant_id),
    INDEX idx_tenant_user (tenant_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**实现接口：** `AgentStateStore.create / findById / listByTenant / updateState / close / delete`

---

### agent_turns

```sql
CREATE TABLE agent_turns (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id          VARCHAR(64)  NOT NULL,
    turn_order          INT          NOT NULL,
    user_msg_json       MEDIUMTEXT   NOT NULL,
    assistant_msg_json  MEDIUMTEXT   DEFAULT NULL,
    tool_calls_json     MEDIUMTEXT   DEFAULT NULL,
    tool_results_json   MEDIUMTEXT   DEFAULT NULL,
    prompt_tokens       INT          DEFAULT 0,
    completion_tokens   INT          DEFAULT 0,
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session (session_id),
    INDEX idx_order   (session_id, turn_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**实现接口：** `AgentStateStore.addTurn / getHistory`

---

### agent_checkpoints

```sql
CREATE TABLE agent_checkpoints (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id            VARCHAR(64)  NOT NULL,
    agent_name            VARCHAR(128) NOT NULL,
    iteration             INT          NOT NULL DEFAULT 0,
    messages_json         MEDIUMTEXT   NOT NULL,
    tool_context_json     TEXT         DEFAULT NULL,
    total_tokens          BIGINT       DEFAULT 0,
    shutdown_interrupted  TINYINT(1)   DEFAULT 0,
    created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**实现接口：** `AgentStateStore.saveCheckpoint / loadLatestCheckpoint / deleteCheckpoints`

---

### agent_audit_logs

```sql
CREATE TABLE agent_audit_logs (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id   VARCHAR(64)  NOT NULL,
    user_id     VARCHAR(64)  DEFAULT NULL,
    session_id  VARCHAR(64)  DEFAULT NULL,
    agent_name  VARCHAR(128) NOT NULL,
    event_type  VARCHAR(64)  NOT NULL,
    tool_name   VARCHAR(128) DEFAULT NULL,
    detail      TEXT         DEFAULT NULL,
    result      VARCHAR(16)  DEFAULT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_tenant_time (tenant_id, created_at),
    INDEX idx_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**写入层：** 在自己的 `AuditHook.onEvent()` 实现中直接写表。`HookContext` 已提供 `tenantId/userId/sessionId/agentName`。

---

### agent_token_usage

```sql
CREATE TABLE agent_token_usage (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id         VARCHAR(64)  NOT NULL,
    user_id           VARCHAR(64)  DEFAULT NULL,
    session_id        VARCHAR(64)  DEFAULT NULL,
    agent_name        VARCHAR(128) NOT NULL,
    model_name        VARCHAR(128) NOT NULL,
    turn_order        INT          DEFAULT NULL,
    prompt_tokens     INT          NOT NULL DEFAULT 0,
    completion_tokens INT          NOT NULL DEFAULT 0,
    total_tokens      INT          NOT NULL DEFAULT 0,
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_tenant_time (tenant_id, created_at),
    INDEX idx_tenant_day  (tenant_id, (CAST(created_at AS DATE)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**写入层：** 在 `PostCallHook` 或 `PostReasoningHook` 实现中写表。每轮 LLM 响应带 `ChatUsage`。

---

### agent_memories（可选）

```sql
CREATE TABLE agent_memories (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    user_id         VARCHAR(64)  DEFAULT NULL,
    content         TEXT         NOT NULL,
    embedding_json  MEDIUMTEXT   DEFAULT NULL,
    metadata_json   TEXT         DEFAULT NULL,
    access_count    INT          DEFAULT 0,
    last_access_at  DATETIME     DEFAULT NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_tenant_user (tenant_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**实现接口：** `Memory.store / retrieve / forget / clear`

---

### agent_tenant_tools（可选）

```sql
CREATE TABLE agent_tenant_tools (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id        VARCHAR(64)  NOT NULL,
    tool_name        VARCHAR(128) NOT NULL,
    tool_group       VARCHAR(64)  DEFAULT 'default',
    scope            VARCHAR(16)  NOT NULL DEFAULT 'TENANT',
    tool_schema_json TEXT         NOT NULL,
    enabled          TINYINT(1)   NOT NULL DEFAULT 1,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tenant_tool (tenant_id, tool_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**加载层：** 启动/运行时查表 → 反序列化 → `toolRegistry.registerForTenant(tenantId, tool)`。

---

### agent_credentials（可选）

```sql
CREATE TABLE agent_credentials (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id   VARCHAR(64)  NOT NULL,
    provider    VARCHAR(64)  NOT NULL,
    model_name  VARCHAR(128) DEFAULT NULL,
    api_key_enc VARCHAR(512) NOT NULL,
    base_url    VARCHAR(512) DEFAULT NULL,
    enabled     TINYINT(1)   NOT NULL DEFAULT 1,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tenant_provider (tenant_id, provider)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**加载层：** 实现一个 `TenantAwareChatModel implements ChatModel`，`chat()` 时根据 `tenantId` 查此表获取对应 apiKey → 创建具体模型实例调用。

---

## 二、请求 DB 操作序列

```
chat(messages, RuntimeContext{tenantId, userId, sessionId})
│
├─ AgentStateStore.findById(sessionId)          → agent_sessions
├─ AgentStateStore.getHistory(sessionId)        → agent_turns
├─ AgentStateStore.loadLatestCheckpoint(sessionId) → agent_checkpoints
│
├─ [ReAct 循环]
│   └─ AgentStateStore.saveCheckpoint(state)    → agent_checkpoints
│
├─ AgentStateStore.addTurn(sessionId, turn)     → agent_turns
└─ [Hook 层异步]
    ├─ agent_audit_logs  (AuditHook)
    └─ agent_token_usage (PostCallHook)
```

---

## 三、你只需做

1. 建表（上面 DDL）
2. 写一个 `MysqlAgentStateStore implements AgentStateStore`
3. 写一个 `MysqlMemory implements Memory`
4. 注入：

```java
HarnessAgent.builder()
    .stateStore(new MysqlAgentStateStore(dataSource))
    .hook(new DbAuditHook(dataSource))   // 写 agent_audit_logs
    .build();
```
