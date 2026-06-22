package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.model.ChatResponse;
import cd.lan1akea.core.model.GenerateOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * ReAct 循环上下文 — 纯数据对象。
 * <p>
 * 每次请求创建新实例，仅携带当前请求的可变状态。
 * 不持有任何子系统引用（SessionStore、Memory、PermissionEngine 等），
 * 那些是 Agent 构建时注入到 ReActLoop 的服务，通过方法参数使用。
 * </p>
 */
public class LoopContext {

    private final String agentName;
    private final String tenantId;
    private final String userId;
    private final String sessionId;
    private final List<Msg> messages;
    private final GenerateOptions generateOptions;
    private final int maxIterations;
    private final boolean stream;
    private int iteration;
    private ChatResponse lastResponse;
    private long totalTokens;

    private LoopContext(Builder builder) {
        this.agentName = builder.agentName;
        this.tenantId = builder.tenantId;
        this.userId = builder.userId;
        this.sessionId = builder.sessionId;
        this.messages = new ArrayList<>(builder.messages);
        this.generateOptions = builder.generateOptions;
        this.maxIterations = builder.maxIterations;
        this.stream = builder.stream;
        this.iteration = 0;
        this.totalTokens = 0;
    }

    public void addMessage(Msg msg) { messages.add(msg); }
    public void addMessages(List<Msg> msgs) { messages.addAll(msgs); }
    public void addTokens(long tokens) { this.totalTokens += tokens; }

    public String getAgentName() { return agentName; }
    public String getTenantId() { return tenantId; }
    public String getUserId() { return userId; }
    public String getSessionId() { return sessionId; }
    public List<Msg> getMessages() { return messages; }
    public GenerateOptions getGenerateOptions() { return generateOptions; }
    public int getMaxIterations() { return maxIterations; }
    public boolean isStream() { return stream; }
    public int getIteration() { return iteration; }
    public void setIteration(int iteration) { this.iteration = iteration; }
    public ChatResponse getLastResponse() { return lastResponse; }
    public void setLastResponse(ChatResponse lastResponse) { this.lastResponse = lastResponse; }
    public long getTotalTokens() { return totalTokens; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String agentName;
        private String tenantId;
        private String userId;
        private String sessionId;
        private List<Msg> messages;
        private GenerateOptions generateOptions;
        private int maxIterations = 10;
        private boolean stream;

        public Builder agentName(String v) { this.agentName = v; return this; }
        public Builder tenantId(String v) { this.tenantId = v; return this; }
        public Builder userId(String v) { this.userId = v; return this; }
        public Builder sessionId(String v) { this.sessionId = v; return this; }
        public Builder messages(List<Msg> v) { this.messages = v; return this; }
        public Builder generateOptions(GenerateOptions v) { this.generateOptions = v; return this; }
        public Builder maxIterations(int v) { this.maxIterations = v; return this; }
        public Builder stream(boolean v) { this.stream = v; return this; }

        public LoopContext build() { return new LoopContext(this); }
    }
}
