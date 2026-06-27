package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.context.RuntimeContext;
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
    private final GenerateOptions generateOptions;
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
     * 从 builder 创建 LoopContext。
     *
     * @param builder 配置了字段的 builder
     */
    private LoopContext(Builder builder) {
        this.agentName = builder.agentName;
        this.tenantId = builder.tenantId;
        this.userId = builder.userId;
        this.sessionId = builder.sessionId;
        this.attributes = builder.attributes != null
            ? Collections.unmodifiableMap(new HashMap<>(builder.attributes))
            : Collections.emptyMap();
        this.messages = new ArrayList<>(builder.messages);
        this.generateOptions = builder.generateOptions;
        this.maxIterations = builder.maxIterations;
        this.stream = builder.stream;
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
     * 创建 Builder。
     *
     * @return 新的 Builder
     */
    public static Builder builder() { return new Builder(); }

    /**
     * LoopContext 建造者。
     */
    public static class Builder {
        private String agentName;
        private String tenantId;
        private String userId;
        private String sessionId;
        private Map<String, Object> attributes;
        private List<Msg> messages;
        private GenerateOptions generateOptions;
        private int maxIterations = 10;
        private boolean stream;

        /**
         * 从 RuntimeContext 复制身份字段，消除手动字段赋值。
         * agentName 必须单独设置，因为 RuntimeContext 的 agentName 可能为 null。
         *
         * @param ctx 运行时上下文
         * @return 当前 builder
         */
        public Builder fromRuntimeContext(RuntimeContext ctx) {
            if (ctx != null) {
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
         * 构建 LoopContext。
         *
         * @return 新的 LoopContext
         */
        public LoopContext build() { return new LoopContext(this); }
    }
}
