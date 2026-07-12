package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.context.RuntimeContext;
import cd.lan1akea.core.hook.HookContext;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.model.ChatResponse;
import cd.lan1akea.core.model.GenerateOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ReAct 循环上下文 — 纯数据对象。
 * 每次请求创建新实例，仅携带当前请求的可变状态。
 * 不持有任何子系统引用（SessionStore、Memory、PermissionEngine 等），
 * 那些是 Agent 构建时注入到 ReActLoop 的服务，通过方法参数使用。
 */
public class LoopContext {

    /**
     * Agent 名称。
     */
    private final String agentName;
    /**
     * 请求追踪 ID。
     */
    private final String requestId;
    /**
     * 租户标识。
     */
    private final String tenantId;
    /**
     * 用户标识。
     */
    private final String userId;
    /**
     * 会话标识。
     */
    private final String sessionId;
    /**
     * 额外上下文属性。
     */
    private final Map<String, Object> attributes;
    /**
     * 本次循环执行的消息列表。
     */
    private final List<Msg> messages;
    /**
     * 生成选项。
     */
    private volatile GenerateOptions generateOptions;
    /**
     * 最大 ReAct 迭代次数。
     */
    private final int maxIterations;
    /**
     * 是否流式执行。
     */
    private final boolean stream;
    /**
     * 当前迭代次数。
     */
    private int iteration;
    /**
     * 最后收到的响应。
     */
    private ChatResponse lastResponse;
    /**
     * 累计 token 数。
     */
    private long totalTokens;
    /**
     * 是否已中断。
     */
    private volatile boolean interrupted;
    /**
     * 中断时注入的反馈消息。
     */
    private Msg feedbackMsg;
    /**
     * 迭代间退避延迟（毫秒），0 表示无退避。
     */
    private final long backoffMs;
    /**
     * 会话是否已完成（无需继续推理）。
     * 由引擎在 REASON 阶段评估无工具调用时标记，
     * Guard 阶段检查此标记决定 Stop。
     */
    private volatile boolean complete;

    /** 标记会话完成，下一轮 Guard 评估时将返回 Stop */
    public void markComplete() { this.complete = true; }

    /** @return 会话是否已完成 */
    public boolean isComplete() { return complete; }

    /** 人工介入状态 */
    private final InterventionState interventionState = new InterventionState();

    /** @return 人工介入状态 */
    public InterventionState getInterventionState() { return interventionState; }


    /**
     * 从 builder 创建 LoopContext。
     *
     * @param builder 配置了字段的 builder
     */
    private LoopContext(Builder builder) {
        this.agentName = builder.agentName;
        this.requestId = builder.requestId != null
            ? builder.requestId : java.util.UUID.randomUUID().toString();
        this.tenantId = builder.tenantId;
        this.userId = builder.userId;
        this.sessionId = builder.sessionId;
        this.attributes = builder.attributes != null
            ? Collections.unmodifiableMap(new HashMap<>(builder.attributes))
            : Collections.emptyMap();
        this.messages = builder.messages != null
                ? new ArrayList<>(builder.messages) : new ArrayList<>();
        this.generateOptions = builder.generateOptions != null
                ? builder.generateOptions : GenerateOptions.defaults();
        this.maxIterations = builder.maxIterations;
        this.stream = builder.stream;
        this.backoffMs = builder.backoffMs;
        this.iteration = 0;
        this.totalTokens = 0;
    }

    /**
     * 向上下文添加消息。
     *
     * @param msg 要添加的消息
     */
    public void addMessage(Msg msg) { messages.add(msg); }
    /**
     * 应用模型响应，写入 lastResponse + 累加 token + 追加 assistant 消息。
     *
     * @param resp 模型响应
     */
    public void applyResponse(ChatResponse resp) {
        this.lastResponse = resp;
        if (resp.getUsage() != null) this.totalTokens += resp.getUsage().getTotalTokens();
        Msg msg = resp.getMessage();
        if (msg != null) this.messages.add(msg);
    }
    /**
     * 向上下文添加多条消息。
     *
     * @param msgs 要添加的消息列表
     */
    public void addMessages(List<Msg> msgs) { messages.addAll(msgs); }
    /**
     * 累加 token 数。
     *
     * @param tokens 要添加的 token 数
     */
    public void addTokens(long tokens) { this.totalTokens += tokens; }

    /**
     * @return Agent 名称
     */
    public String getAgentName() { return agentName; }
    /**
     * @return 请求追踪 ID
     */
    public String getRequestId() { return requestId; }
    /**
     * @return 租户标识
     */
    public String getTenantId() { return tenantId; }
    /**
     * @return 用户标识
     */
    public String getUserId() { return userId; }
    /**
     * @return 会话标识
     */
    public String getSessionId() { return sessionId; }
    /**
     * @return 属性映射
     */
    public Map<String, Object> getAttributes() { return attributes; }
    /**
     * 按 key 获取属性。
     *
     * @param key 属性 key
     * @param <T> 期望的类型
     * @return 属性值或 null
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) { return (T) attributes.get(key); }
    /**
     * @return 消息列表
     */
    public List<Msg> getMessages() { return messages; }
    /**
     * @return 生成选项
     */
    public GenerateOptions getGenerateOptions() { return generateOptions; }
    /** 覆盖生成选项（如达到最大迭代时禁用工具/限制Token） */
    public void setGenerateOptions(GenerateOptions v) { this.generateOptions = v; }
    /**
     * @return 最大迭代次数
     */
    public int getMaxIterations() { return maxIterations; }
    /**
     * @return 是否启用流式
     */
    public boolean isStream() { return stream; }
    /**
     * @return 当前迭代次数
     */
    public int getIteration() { return iteration; }
    /**
     * 设置当前迭代次数。
     */
    public void setIteration(int iteration) { this.iteration = iteration; }
    /**
     * @return 最后响应
     */
    public ChatResponse getLastResponse() { return lastResponse; }
    /**
     * 设置最后响应。
     */
    public void setLastResponse(ChatResponse lastResponse) { this.lastResponse = lastResponse; }
    /**
     * @return 累计 token 数
     */
    public long getTotalTokens() { return totalTokens; }

    /**
     * 设置中断标志以停止执行。
     */
    public void interrupt() { this.interrupted = true; }

    /**
     * 设置中断标志并注入反馈。
     *
     * @param feedback 反馈消息
     */
    public void interrupt(Msg feedback) { this.interrupted = true; this.feedbackMsg = feedback; }

    /**
     * 清除中断标志以恢复执行。
     */
    public void clearInterrupt() { this.interrupted = false; this.feedbackMsg = null; }

    /**
     * @return 是否已中断
     */
    public boolean isInterrupted() { return interrupted; }
    /**
     * @return 中断时的反馈消息
     */
    public Msg getFeedbackMsg() { return feedbackMsg; }
    /**
     * @return 迭代间退避延迟（毫秒）
     */
    public long getBackoffMs() { return backoffMs; }

    /**
     * 从当前循环上下文构建 Hook 上下文。
     *
     * @return 新的 HookContext，包含当前循环的所有身份和状态信息
     */
    public HookContext toHookContext() {
        return new HookContext(agentName, requestId, tenantId, sessionId,
                userId, iteration, List.of(), attributes);
    }


    /**
     * 创建 Builder。
     *
     * @return 新的 Builder
     */
    public static Builder builder() { return new Builder(); }

    /**
     * 人工介入状态。
     * <p>三个字段始终一起读写，收拢为单一对象避免字段散落。
     * 字段均为 volatile 以保证跨线程可见性（执行线程与介入 API 线程）。
     */
    public static class InterventionState {
        /** 待解决的介入请求 ID（null 表示无待解决介入） */
        private volatile String interventionId;
        /** 介入类型名称（APPROVAL/CLARIFY） */
        private volatile String interventionType;
        /** 暂停时快照的工具参数 JSON */
        private volatile String pausedToolArgs;
        /** 暂停原因（工具自定义描述） */
        private volatile String pausedReason;

        /** @return 待解决的介入请求 ID */
        public String getInterventionId() { return interventionId; }
        /** 设置待解决的介入请求 ID */
        public void setInterventionId(String v) { this.interventionId = v; }
        /** @return 介入类型名称 */
        public String getInterventionType() { return interventionType; }
        /** 设置介入类型名称 */
        public void setInterventionType(String v) { this.interventionType = v; }
        /** @return 暂停时快照的工具参数 JSON */
        public String getPausedToolArgs() { return pausedToolArgs; }
        /** 设置暂停时快照的工具参数 JSON */
        public void setPausedToolArgs(String v) { this.pausedToolArgs = v; }
        /** @return 暂停原因（工具自定义描述） */
        public String getPausedReason() { return pausedReason; }
        /** 设置暂停原因（工具自定义描述） */
        public void setPausedReason(String v) { this.pausedReason = v; }

        /** 清除所有介入状态 */
        public void clear() {
            this.interventionId = null;
            this.interventionType = null;
            this.pausedToolArgs = null;
            this.pausedReason = null;
        }

        /** @return 是否有待解决的介入 */
        public boolean hasPending() { return interventionId != null; }
    }

    /**
     * LoopContext 建造者。
     */
    public static class Builder {
        /** Agent 名称 */
        private String agentName;
        /** 请求追踪 ID（null 时自动生成） */
        private String requestId;
        /** 租户标识 */
        private String tenantId;
        /** 用户标识 */
        private String userId;
        /** 会话标识 */
        private String sessionId;
        /** 额外上下文属性 */
        private Map<String, Object> attributes;
        /** 消息列表 */
        private List<Msg> messages;
        /** 生成选项 */
        private GenerateOptions generateOptions;
        /** 最大迭代次数，默认 10 */
        private int maxIterations = 10;
        /** 是否启用流式模式 */
        private boolean stream;
        /** 迭代间退避延迟（毫秒），默认 0 */
        private long backoffMs;

        /**
         * 从 RuntimeContext 复制身份字段，消除手动字段赋值。
         * agentName 必须单独设置，因为 RuntimeContext 的 agentName 可能为 null。
         *
         * @param ctx 运行时上下文
         * @return 当前 builder
         */
        public Builder fromRuntimeContext(RuntimeContext ctx) {
            if (ctx != null) {
                this.requestId = ctx.getRequestId();
                this.tenantId = ctx.getTenantId();
                this.userId = ctx.getUserId();
                this.sessionId = ctx.getSessionId();
                this.attributes = ctx.getAttributes();
            }
            return this;
        }

        /**
         * 设置 Agent 名称。
         */
        public Builder agentName(String v) { this.agentName = v; return this; }
        /**
         * 设置请求追踪 ID（可选，默认自动生成）。
         */
        public Builder requestId(String v) { this.requestId = v; return this; }
        /**
         * 设置租户 ID。
         */
        public Builder tenantId(String v) { this.tenantId = v; return this; }
        /**
         * 设置用户 ID。
         */
        public Builder userId(String v) { this.userId = v; return this; }
        /**
         * 设置会话 ID。
         */
        public Builder sessionId(String v) { this.sessionId = v; return this; }
        /**
         * 设置属性映射。
         */
        public Builder attributes(Map<String, Object> v) { this.attributes = v; return this; }
        /**
         * 设置消息列表。
         */
        public Builder messages(List<Msg> v) { this.messages = v; return this; }
        /**
         * 设置生成选项。
         */
        public Builder generateOptions(GenerateOptions v) { this.generateOptions = v; return this; }
        /**
         * 设置最大迭代次数。
         */
        public Builder maxIterations(int v) { this.maxIterations = v; return this; }
        /**
         * 设置流式模式。
         */
        public Builder stream(boolean v) { this.stream = v; return this; }
        /**
         * 设置迭代间退避延迟（毫秒）。
         */
        public Builder backoffMs(long v) { this.backoffMs = v; return this; }

        /**
         * 构建 LoopContext。
         *
         * @return 新的 LoopContext
         */
        public LoopContext build() { return new LoopContext(this); }
    }
}
