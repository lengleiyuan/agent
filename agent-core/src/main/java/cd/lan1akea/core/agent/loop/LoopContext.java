package cd.lan1akea.core.agent.loop;

import cd.lan1akea.core.event.EventBus;
import cd.lan1akea.core.memory.Memory;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.model.ChatResponse;
import cd.lan1akea.core.model.GenerateOptions;
import cd.lan1akea.core.model.ModelContextWindow;
import cd.lan1akea.core.session.SessionStore;
import cd.lan1akea.core.session.SessionSummaryService;
import cd.lan1akea.core.tenant.PermissionEngine;
import cd.lan1akea.core.tool.ToolValidator;
import cd.lan1akea.core.hook.recorder.HookRecorder;
import cd.lan1akea.core.workspace.Workspace;

import java.util.ArrayList;
import java.util.List;

/**
 * ReAct 循环上下文。
 * <p>
 * 携带循环执行所需的全部运行时状态和子系统引用。
 * 包括消息历史、Token 计数、会话持久化、权限校验、记忆检索等。
 * </p>
 */
public class LoopContext {

    // === Agent 标识 ===
    private final String agentName;
    private final String tenantId;
    private final String userId;
    private final String sessionId;

    // === 消息与对话状态 ===
    private final List<Msg> messages;
    private final GenerateOptions generateOptions;
    private final int maxIterations;
    private final boolean stream;
    private int iteration;
    private ChatResponse lastResponse;
    private String lastToolCallId;
    private long totalTokens;

    // === 子系统引用（通过 Builder 注入） ===
    private final SessionStore sessionStore;
    private final SessionSummaryService summaryService;
    private final PermissionEngine permissionEngine;
    private final EventBus eventBus;
    private final Memory memory;
    private final Workspace workspace;
    private final ModelContextWindow contextWindow;
    private final ToolValidator toolValidator;
    private final HookRecorder hookRecorder;

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

        this.sessionStore = builder.sessionStore;
        this.summaryService = builder.summaryService;
        this.permissionEngine = builder.permissionEngine;
        this.eventBus = builder.eventBus;
        this.memory = builder.memory;
        this.workspace = builder.workspace;
        this.contextWindow = builder.contextWindow;
        this.toolValidator = builder.toolValidator;
        this.hookRecorder = builder.hookRecorder;
    }

    // === 消息管理 ===

    public void addMessage(Msg msg) {
        messages.add(msg);
    }

    public void addMessages(List<Msg> msgs) {
        messages.addAll(msgs);
    }

    /**
     * 追加 Token 计数（每次 LLM 调用后更新）。
     */
    public void addTokens(long tokens) {
        this.totalTokens += tokens;
    }

    /**
     * 判断是否接近上下文窗口上限，需要压缩。
     */
    public boolean needsCompression() {
        if (contextWindow == null || summaryService == null) return false;
        double usage = (double) totalTokens / contextWindow.getMaxInputTokens();
        return usage > 0.75; // 使用率超过 75% 触发压缩
    }

    /**
     * 压缩早期消息历史，生成摘要并替换。
     */
    public Msg compressHistory() {
        if (summaryService == null || messages.size() < 4) return null;
        // 保留最近 2 轮对话，压缩更早的消息
        int compressCount = messages.size() - 4;
        if (compressCount <= 0) return null;

        List<Msg> toCompress = new ArrayList<>(messages.subList(0, compressCount));
        // 移除旧的，替换为摘要
        messages.subList(0, compressCount).clear();

        // 构建模拟 ChatTurn 列表用于摘要
        List<cd.lan1akea.core.session.ChatTurn> turns = new ArrayList<>();
        for (int i = 0; i < toCompress.size(); i += 2) {
            String userJson = i < toCompress.size() ? toCompress.get(i).getTextContent() : "";
            String assistantJson = i + 1 < toCompress.size() ? toCompress.get(i + 1).getTextContent() : "";
            turns.add(new cd.lan1akea.core.session.ChatTurn(
                0, 0, i / 2, userJson, assistantJson, null, java.time.LocalDateTime.now()));
        }

        Msg summary = summaryService.summarize(turns);
        messages.add(0, summary);
        return summary;
    }

    // === 会话持久化 ===

    /**
     * 将当前轮次持久化到 Session。
     */
    public void persistTurn(Msg userMsg, Msg assistantMsg, String toolCallsJson) {
        if (sessionStore == null || sessionId == null) return;
        cd.lan1akea.core.session.ChatTurn turn = new cd.lan1akea.core.session.ChatTurn(
            cd.lan1akea.core.util.IdGenerator.nextId(),
            Long.parseLong(sessionId),
            iteration,
            userMsg.getTextContent(),
            assistantMsg != null ? assistantMsg.getTextContent() : null,
            toolCallsJson,
            java.time.LocalDateTime.now()
        );
        sessionStore.addTurn(new cd.lan1akea.core.session.SessionId(sessionId), turn).subscribe();
    }

    // === 权限校验 ===

    /**
     * 校验工具执行权限。
     */
    public cd.lan1akea.core.tenant.PermissionDecision checkPermission(String toolName) {
        if (permissionEngine == null || userId == null) {
            return cd.lan1akea.core.tenant.PermissionDecision.allow();
        }
        cd.lan1akea.core.tenant.User user = new cd.lan1akea.core.tenant.User(
            new cd.lan1akea.core.tenant.UserId(Long.parseLong(userId)),
            tenantId != null ? Long.parseLong(tenantId) : 0,
            "agent-user", "ACTIVE",
            java.util.Collections.emptyList(),
            java.time.LocalDateTime.now()
        );
        return permissionEngine.evaluate(user,
            cd.lan1akea.core.tenant.ResourceType.TOOL, "execute");
    }

    // === 记忆检索 ===

    /**
     * 从长期记忆中检索相关上下文，追加到消息列表。
     */
    public void enrichFromMemory(String query) {
        if (memory == null) return;
        try {
            java.util.List<cd.lan1akea.core.memory.MemoryEntry> entries = memory.retrieve(
                new cd.lan1akea.core.memory.MemoryRetrievalQuery(
                    query, 3,
                    tenantId != null ? Long.parseLong(tenantId) : null,
                    userId != null ? Long.parseLong(userId) : null
                )).collectList().block(java.time.Duration.ofSeconds(5));
            if (entries != null && !entries.isEmpty()) {
                StringBuilder sb = new StringBuilder("相关记忆:\n");
                for (cd.lan1akea.core.memory.MemoryEntry entry : entries) {
                    sb.append("- ").append(entry.getContent()).append("\n");
                }
                messages.add(0, cd.lan1akea.core.message.SystemMessage.of(sb.toString()));
            }
        } catch (Exception ignored) {
            // 记忆检索失败不影响主流程
        }
    }

    // === Getters / Setters ===

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
    public String getLastToolCallId() { return lastToolCallId; }
    public void setLastToolCallId(String lastToolCallId) { this.lastToolCallId = lastToolCallId; }
    public long getTotalTokens() { return totalTokens; }

    // 子系统 Getters
    public EventBus getEventBus() { return eventBus; }
    public HookRecorder getHookRecorder() { return hookRecorder; }
    public Workspace getWorkspace() { return workspace; }
    public ToolValidator getToolValidator() { return toolValidator; }
    public PermissionEngine getPermissionEngine() { return permissionEngine; }
    public Memory getMemory() { return memory; }
    public SessionStore getSessionStore() { return sessionStore; }

    public static Builder builder() { return new Builder(); }

    // === Builder ===

    public static class Builder {
        private String agentName;
        private String tenantId;
        private String userId;
        private String sessionId;
        private List<Msg> messages;
        private GenerateOptions generateOptions;
        private int maxIterations = 10;
        private boolean stream;

        // 子系统
        private SessionStore sessionStore;
        private SessionSummaryService summaryService;
        private PermissionEngine permissionEngine;
        private EventBus eventBus;
        private Memory memory;
        private Workspace workspace;
        private ModelContextWindow contextWindow;
        private ToolValidator toolValidator;
        private HookRecorder hookRecorder;

        public Builder agentName(String v) { this.agentName = v; return this; }
        public Builder tenantId(String v) { this.tenantId = v; return this; }
        public Builder userId(String v) { this.userId = v; return this; }
        public Builder sessionId(String v) { this.sessionId = v; return this; }
        public Builder messages(List<Msg> v) { this.messages = v; return this; }
        public Builder generateOptions(GenerateOptions v) { this.generateOptions = v; return this; }
        public Builder maxIterations(int v) { this.maxIterations = v; return this; }
        public Builder stream(boolean v) { this.stream = v; return this; }
        public Builder sessionStore(SessionStore v) { this.sessionStore = v; return this; }
        public Builder summaryService(SessionSummaryService v) { this.summaryService = v; return this; }
        public Builder permissionEngine(PermissionEngine v) { this.permissionEngine = v; return this; }
        public Builder eventBus(EventBus v) { this.eventBus = v; return this; }
        public Builder memory(Memory v) { this.memory = v; return this; }
        public Builder workspace(Workspace v) { this.workspace = v; return this; }
        public Builder contextWindow(ModelContextWindow v) { this.contextWindow = v; return this; }
        public Builder toolValidator(ToolValidator v) { this.toolValidator = v; return this; }
        public Builder hookRecorder(HookRecorder v) { this.hookRecorder = v; return this; }

        public LoopContext build() { return new LoopContext(this); }
    }
}
