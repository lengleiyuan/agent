package cd.lan1akea.core;

/**
 * 框架核心常量定义。
 * 主链路（ReActAgent → ReActLoop → ToolExecutor → Hook）的所有字符串常量集中管理。
 *
 * <p>分类语义：</p>
 * <ul>
 *   <li>{@link RuntimeCtx} — Reactor Context / RuntimeContext 的键</li>
 *   <li>{@link EventPayload} — HookEvent payload 的键</li>
 *   <li>{@link FinishReason} — LLM 响应的结束原因</li>
 *   <li>{@link HookSource} — Hook 来源标识（用于 AbortException）</li>
 *   <li>{@link Msg} — 用户可见的错误/状态消息</li>
 *   <li>{@link Prompt} — 系统提示词模板</li>
 *   <li>{@link Defaults} — 默认值</li>
 * </ul>
 */
public final class CoreConstants {

    private CoreConstants() {}

    /**
     * Reactor Context / RuntimeContext 传播键。
     * 用于跨线程/跨 Agent 传递租户、用户、会话等上下文。
     */
public static final class RuntimeCtx {
        /** 请求追踪 ID */
        public static final String REQUEST_ID = "requestId";
        /** 租户标识 */
        public static final String TENANT_ID = "tenantId";
        /** 用户标识 */
        public static final String USER_ID = "userId";
        /** 会话标识 */
        public static final String SESSION_ID = "sessionId";
        /** 扩展属性 */
        public static final String ATTRIBUTES = "attributes";

        private RuntimeCtx() {}
    }

    /**
     * HookEvent payload 键。
     * 用于在 PRE → POST / AroundHook 链中传递数据。
     */
    public static final class EventPayload {
        /** 非流式聊天响应 */
        public static final String RESPONSE = "response";
        /** 模型调用响应（AroundHook 内传递） */
        public static final String CHAT_RESPONSE = "chat_response";
        /** 工具调用参数（InterruptEvent） */
        public static final String ARGUMENTS = "arguments";
        /** 最近消息列表（InterruptEvent） */
        public static final String RECENT_MESSAGES = "recentMessages";
        /** 工具描述（InterruptEvent） */
        public static final String TOOL_DESCRIPTION = "toolDescription";
        /** 风险等级（InterruptEvent） */
        public static final String RISK_LEVEL = "riskLevel";
        /** LoopContext 引用（AFTER_ITERATION） */
        public static final String LOOP_CONTEXT = "loopContext";
        /** 工具执行结果（ToolCallOrchestrator → LoopExecutor） */
        public static final String TOOL_RESULT = "tool_result";
        /** 干预 chunk payload 键 */
        public static final String TYPE = "type";
        /** 干预 chunk payload 键 */
        public static final String INTERVENTION_ID = "interventionId";
        /** 干预 chunk payload 键 */
        public static final String QUESTION = "question";
        /** 干预 chunk payload 键 */
        public static final String INTERVENTION_TYPE = "interventionType";
        /** 干预 chunk payload 键 */
        public static final String TOOL_NAME = "toolName";
        /** 中断消息 metadata key（buildInterruptedResponse） */
        public static final String INTERRUPT_ID = "interruptId";
        /** 消息列表（HookEvent payload） */
        public static final String MESSAGES = "messages";
        /** 绕过模型调用的直接回复消息 */
        public static final String BYPASS_MESSAGE = "bypassMessage";

        private EventPayload() {}
    }

    /**
     * LLM 响应 finish_reason 常量。
     */
    public static final class FinishReason {
        /** 正常结束 */
        public static final String STOP = "stop";
        /** 已被中断 */
        public static final String INTERRUPTED = "interrupted";
        /** 流式默认为完成 */
        public static final String COMPLETED = "completed";

        private FinishReason() {}
    }

    /**
     * Hook 来源标识。
     * 用于 HookAbortException 中标记是哪个环节触发了终止。
     */
    public static final class HookSource {
        /** 通用 Hook */
        public static final String HOOK = "hook";
        /** 模型调用环节 */
        public static final String MODEL = "model";
        /** 错误处理 Hook */
        public static final String ERROR_HOOK = "ErrorHook";

        private HookSource() {}
    }

    /**
     * 用户可见的消息文本（工具结果、错误提示、中断消息）。
     */
    public static final class UI {
        /** 工具执行异常前缀 */
        public static final String TOOL_ERROR_PREFIX = "[错误] ";
        /** 工具被阻止 */
        public static final String TOOL_BLOCKED = "工具调用被阻止: ";
        /** 工具被跳过前缀 */
        public static final String TOOL_SKIPPED_PREFIX = "[已跳过] ";
        /** 工具被跳过默认原因 */
        public static final String TOOL_SKIPPED_DEFAULT = "无权限";
        /** 工具无结果 */
        public static final String TOOL_NO_RESULT = "无结果";
        /** 工具不存在 */
        public static final String TOOL_NOT_FOUND = "工具不存在: ";
        /** 工具在上下文中不可用 */
        public static final String TOOL_CTX_UNAVAILABLE = "。上下文 [";
        /** 工具不可用后缀 */
        public static final String TOOL_CTX_UNAVAILABLE_SUFFIX = "] 中不可用";
        /** 参数校验失败 */
        public static final String TOOL_PARAM_INVALID = "参数校验失败: ";
        /** 工具执行异常 */
        public static final String TOOL_EXEC_ERROR = "工具执行异常: ";
        /** 工具执行超时 */
        public static final String TOOL_TIMEOUT = "工具执行超时 (";
        /** 工具执行超时后缀 */
        public static final String TOOL_TIMEOUT_SUFFIX = "ms)";
        /** 超时异常消息 */
        public static final String TOOL_TIMEOUT_EX = "执行超时";
        /** 操作被审批拒绝 */
        public static final String APPROVAL_DENIED = "操作被拒绝";
        /** 等待审批 */
        public static final String APPROVAL_WAITING = "等待审批: ";
        /** 外部中断 */
        public static final String INTERRUPT_EXTERNAL = "外部中断";
        /** 执行已被中断 */
        public static final String INTERRUPT_EXEC = "执行已被中断";
        /** 中断消息前缀 */
        public static final String INTERRUPT_PREFIX = "[执行已中断: ";
        /** 中断消息后缀 */
        public static final String INTERRUPT_SUFFIX = "]";
        /** 流式中断前缀 */
        public static final String INTERRUPT_STREAM_PREFIX = "[中断: ";

        /** Agent 名称不能为空 */
        public static final String AGENT_NAME_REQUIRED = "Agent name 不能为空";
        /** ChatModel 不能为 null */
        public static final String MODEL_REQUIRED = "ChatModel 不能为 null";
        /** Agent 已构建 */
        public static final String AGENT_ALREADY_BUILT = " 已构建";
        /** Agent 尚未构建 */
        public static final String AGENT_NOT_BUILT = " 尚未构建";
        /** Agent 前缀 */
        public static final String AGENT_PREFIX = "Agent [";

        private UI() {}
    }

    /**
     * 系统提示词模板。
     */
    public static final class Prompt {
        /** 达到最大迭代时让 LLM 总结的提示词 */
        public static final String MAX_ITERATIONS_SUMMARY =
            "你已达到最大迭代次数。请用一段话总结你已经完成的工作和当前状态。";
        /** 总结时禁止调用工具 */
        public static final String MAX_ITERATIONS_NO_TOOLS = "不要调用任何工具，只做文字总结。";

        private Prompt() {}
    }

    /**
     * 默认值。
     */
    public static final class Defaults {
        /** 默认租户 */
        public static final String TENANT = "default";
        /** 全局上下文 */
        public static final String CONTEXT_GLOBAL = "global";

        private Defaults() {}
    }

    /**
     * 日志消息模板。
     */
    public static final class Logs {
        /** AFTER_ITERATION Hook 失败 */
        public static final String AFTER_ITERATION_FAILED = "AFTER_ITERATION hook failed: rid=";
        /** 错误详情 */
        public static final String ERR_DETAIL = " err=";

        private Logs() {}
    }

    /**
     * 人工介入相关常量。
     */
    public static final class Intervention {
        /** 介入信号 chunk 类型 */
        public static final String CHUNK_TYPE = "intervention";
        /** 介入信号 payload type */
        public static final String PAYLOAD_TYPE = "intervention_required";
        /** 默认风险等级 */
        public static final String DEFAULT_RISK_LEVEL = "MEDIUM";
        /** 默认介入 TTL（分钟） */
        public static final int DEFAULT_TTL_MINUTES = 5;
        /** 默认解决人 */
        public static final String DEFAULT_RESOLVER = "resolver";
        /** 最近消息截断条数 */
        public static final int RECENT_MSG_LIMIT = 20;
        /** 介入暂停 chunk 的 finish_reason */
        public static final String FINISH_REASON = "interrupted";

        /** 审批操作：批准 */
        public static final String ACTION_APPROVE = "approve";
        /** 审批操作：拒绝 */
        public static final String ACTION_DENY = "deny";
        /** 审批操作：澄清 */
        public static final String ACTION_CLARIFY = "clarify";
        /** 审批操作：回复 */
        public static final String ACTION_REPLY = "reply";

        /** 工具参数名：介入ID */
        public static final String PARAM_INTERVENTION_ID = "intervention_id";
        /** 工具参数名：操作 */
        public static final String PARAM_ACTION = "action";
        /** 工具参数名：备注 */
        public static final String PARAM_COMMENT = "comment";

        /** 介入拒绝消息 */
        public static final String MSG_DENIED = "上一步操作被拒绝";
        /** 介入过期消息 */
        public static final String MSG_EXPIRED = "上一步操作审批已过期";
        /** 介入暂停提示 */
        public static final String MSG_WAITING = "等待人工处理中...";
        /** 介入恢复调用 ID 前缀 */
        public static final String RESUME_CALL_PREFIX = "resume_";
        /** 介入 pending 错误模板 */
        public static final String ERR_PENDING = "Intervention still pending: ";
        /** 空响应 fallback */
        public static final String EMPTY_REASON = "empty";

        private Intervention() {}
    }

    /**
     * JSON Schema 字段名常量。
     * 用于 ToolBase.getParameters() 构建工具参数 Schema。
     */
    public static final class JsonSchema {
        /** 类型字段 */
        public static final String TYPE = "type";
        /** 对象类型 */
        public static final String TYPE_OBJECT = "object";
        /** 数组类型 */
        public static final String TYPE_ARRAY = "array";
        /** 字符串类型 */
        public static final String TYPE_STRING = "string";
        /** 数字类型 */
        public static final String TYPE_NUMBER = "number";
        /** 布尔类型 */
        public static final String TYPE_BOOLEAN = "boolean";
        /** 数组子项 Schema */
        public static final String ITEMS = "items";
        /** 属性定义 */
        public static final String PROPERTIES = "properties";
        /** 必填字段列表 */
        public static final String REQUIRED = "required";
        /** 字段描述 */
        public static final String DESCRIPTION = "description";
        /** 默认值 */
        public static final String DEFAULT = "default";
        /** 枚举值 */
        public static final String ENUM = "enum";

        private JsonSchema() {}
    }

    /**
     * Token 用量相关常量。
     */
    public static final class Usage {
        /** usage SSE chunk 类型 */
        public static final String CHUNK_TYPE = "usage";
        /** 输入 token 数 */
        public static final String PROMPT_TOKENS = "promptTokens";
        /** 输出 token 数 */
        public static final String COMPLETION_TOKENS = "completionTokens";

        private Usage() {}
    }

    /**
     * 参数校验相关常量。
     */
    public static final class Validation {
        /** AgentConfig 参数名 */
        public static final String PARAM_AGENT_CONFIG = "AgentConfig";
        /** ChatModel 参数名 */
        public static final String PARAM_CHAT_MODEL = "ChatModel";
        /** callParam 参数名 */
        public static final String PARAM_CALL_PARAM = "callParam";

        private Validation() {}
    }
}
