package cd.lan1akea.harness;

import cd.lan1akea.core.agent.ReActAgent;
import cd.lan1akea.core.agent.CallableAgent;
import cd.lan1akea.core.agent.StreamableAgent;
import cd.lan1akea.core.context.RuntimeContext;
import cd.lan1akea.core.exception.AgentConfigurationException;
import cd.lan1akea.core.hook.AroundHook;
import cd.lan1akea.core.hook.Hook;
import cd.lan1akea.core.hook.impl.ContentFilterHook;
import cd.lan1akea.core.hook.impl.LoggingHook;
import cd.lan1akea.core.hook.impl.MemoryEnrichmentHook;
import cd.lan1akea.core.hook.impl.RateLimitHook;
import cd.lan1akea.core.hook.impl.ContextCompressionHook;
import cd.lan1akea.core.hook.impl.ToolAccessHook;
import cd.lan1akea.core.memory.Memory;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.compaction.CompactionContext;
import cd.lan1akea.core.compaction.CompactionStrategy;
import cd.lan1akea.core.compaction.ProgressiveCompactionStrategy;
import cd.lan1akea.core.compaction.SnipCompactionStrategy;
import cd.lan1akea.core.compaction.SummaryCompactionStrategy;
import cd.lan1akea.core.compaction.TrimCompactionStrategy;
import cd.lan1akea.core.model.ModelContextWindow;
import cd.lan1akea.core.model.ChatModel;
import cd.lan1akea.core.model.ChatResponse;
import cd.lan1akea.core.model.ChatStreamChunk;
import cd.lan1akea.core.model.ToolChoicePolicy;
import cd.lan1akea.core.state.AgentStateStore;
import cd.lan1akea.core.state.InMemoryAgentStateStore;
import cd.lan1akea.core.tool.ToolAccessPolicy;
import cd.lan1akea.core.tool.builtin.TodoWriteTool;
import cd.lan1akea.core.tool.mcp.HttpSseTransport;
import cd.lan1akea.core.tool.mcp.McpClient;
import cd.lan1akea.core.tool.mcp.McpToolAdapter;
import cd.lan1akea.harness.support.AnnotationToolAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * HarnessAgent SDK 门面类。
 * 对外唯一入口，封装 Agent 构建和调用的完整生命周期。
 *
 * 示例：
 *     HarnessAgent agent = HarnessAgent.builder()
 *         .name("MyAgent")
 *         .model(new OpenAIChatModel(apiKey, "gpt-4o"))
 *         .tool(new CalculatorTool())
 *         .build();
 *
 *     ChatResponse response = agent.chat(messages).block();
 */
public class HarnessAgent implements StreamableAgent, CallableAgent {

    /**
     * core 层 ReActAgent 委托对象。
     */
    private final ReActAgent delegate;
    /**
     * 需要随 Agent 一起关闭的资源列表。
     */
    private final List<AutoCloseable> closeables = new ArrayList<>();

    /**
     * 基于 core 层 ReActAgent 构造门面 HarnessAgent。
     */
    public HarnessAgent(ReActAgent delegate) {
        this.delegate = delegate;
    }

    /**
     * 注册需要在 shutdown 时关闭的资源。
     */
    void addCloseable(AutoCloseable c) { closeables.add(c); }

    /**
     * 创建 HarnessAgent 构建器。
     */
    public static Builder builder() { return new Builder(); }


    /**
     * 返回 Agent 名称。
     */
    @Override
    public String getName() { return delegate.getName(); }

    /**
     * 返回 Agent ID。
     */
    @Override
    public String getId() { return delegate.getId(); }

    /**
     * 中断当前 Agent 执行。
     */
    @Override
    public void interrupt() { delegate.interrupt(); }

    /**
     * 中断当前 Agent 执行并附带反馈消息。
     */
    @Override
    public void interrupt(Msg feedbackMsg) { delegate.interrupt(feedbackMsg); }

    // ========================================================================
    // CallableAgent
    // ========================================================================

    /**
     * 发送消息并获取对话响应。
     */
    @Override
    public Mono<ChatResponse> chat(List<Msg> messages) {
        return delegate.chat(messages);
    }

    /**
     * 在指定运行时上下文中发送消息。
     */
    @Override
    public Mono<ChatResponse> chat(List<Msg> messages, RuntimeContext ctx) {
        return delegate.chat(messages, ctx);
    }

    /**
     * 发送消息并以指定输出类解析结果。
     */
    @Override
    public Mono<ChatResponse> chat(List<Msg> messages, Class<?> outputClass) {
        return delegate.chat(messages, outputClass);
    }

    /**
     * 流式发送消息并获取增量响应。
     */
    @Override
    public Flux<ChatStreamChunk> stream(List<Msg> messages) {
        return delegate.stream(messages);
    }

    /**
     * 在指定运行时上下文中流式发送消息。
     */
    @Override
    public Flux<ChatStreamChunk> stream(List<Msg> messages, RuntimeContext ctx) {
        return delegate.stream(messages, ctx);
    }

    /**
     * 流式发送消息并以指定输出类解析结果。
     */
    @Override
    public Flux<ChatStreamChunk> stream(List<Msg> messages, Class<?> outputClass) {
        return delegate.stream(messages, outputClass);
    }

    // ========================================================================

    // ========================================================================
    // Harness
    // ========================================================================

    /**
     * 关闭 Agent，释放所有已注册的资源并关闭委托。
     */
    public Mono<Void> shutdown() {
        closeables.forEach(c -> { try { c.close(); } catch (Exception ignored) {} });
        return delegate.shutdown();
    }

    /**
     * 返回 core 层 ReActAgent 委托对象。
     */
    public ReActAgent getDelegate() { return delegate; }

    // ========================================================================
    // Builder
    // ========================================================================

    public static class Builder {
        /**
         * Agent 名称。
         */
        private String name;
        /**
         * 大语言模型实例。
         */
        private ChatModel model;
        /**
         * 工具对象列表。
         */
        private final List<Object> toolObjects = new ArrayList<>();
        /**
         * Hook 列表。
         */
        private final List<Hook> hooks = new ArrayList<>();
        /**
         * 包裹式 Hook 列表。
         */
        private final List<AroundHook> aroundHooks = new ArrayList<>();
        /**
         * Agent 状态存储。
         */
        private AgentStateStore stateStore;
        /**
         * 最大推理轮次。
         */
        private Integer maxIterations;
        /**
         * 采样温度。
         */
        private Double temperature;
        /**
         * 最大输出 token 数。
         */
        private Integer maxTokens;
        /**
         * 工具选择策略。
         */
        private ToolChoicePolicy toolChoice;
        /**
         * 总超时时间（毫秒）。
         */
        private Long totalTimeoutMs;
        /**
         * 租户级工具访问策略。
         */
        private ToolAccessPolicy toolAccessPolicy;
        /**
         * 系统提示词。
         */
        private String systemMessage;
        /**
         * 长期记忆实现。
         */
        private Memory memory;
        /**
         * 自定义压缩策略。
         */
        private CompactionStrategy compactionStrategy;
        /**
         * 是否开启 Plan 模式。
         */
        private boolean enablePlanMode;
        /**
         * 敏感词列表。
         */
        private List<String> contentFilterWords;
        /**
         * MCP 服务器配置列表。
         */
        private final List<McpServerConfig> mcpServers = new ArrayList<>();

        /**
         * 设置 Agent 名称。
         */
        public Builder name(String name) { this.name = name; return this; }
        /**
         * 设置大语言模型。
         */
        public Builder model(ChatModel model) { this.model = model; return this; }

        /**
         * 添加一个工具对象。
         */
        public Builder tool(Object toolObj) { this.toolObjects.add(toolObj); return this; }
        /**
         * 添加多个工具对象。
         */
        public Builder tools(Object... toolObjects) { this.toolObjects.addAll(Arrays.asList(toolObjects)); return this; }
        /**
         * 添加一个 Hook。
         */
        public Builder hook(Hook hook) { this.hooks.add(hook); return this; }
        /**
         * 添加多个 Hook。
         */
        public Builder hooks(Hook... hookList) { this.hooks.addAll(Arrays.asList(hookList)); return this; }
        /**
         * 添加一个包裹式 Hook。
         */
        public Builder aroundHook(AroundHook hook) { this.aroundHooks.add(hook); return this; }
        /**
         * 设置 Agent 状态存储。
         */
        public Builder stateStore(AgentStateStore v) { this.stateStore = v; return this; }
        /**
         * 设置最大推理轮次。
         */
        public Builder maxIterations(int n) { this.maxIterations = n; return this; }
        /**
         * 设置采样温度。
         */
        public Builder temperature(double t) { this.temperature = t; return this; }
        /**
         * 设置最大输出 token 数。
         */
        public Builder maxTokens(int n) { this.maxTokens = n; return this; }
        /**
         * 设置工具选择策略。
         */
        public Builder toolChoice(ToolChoicePolicy p) { this.toolChoice = p; return this; }
        /**
         * 设置总超时时间（毫秒）。
         */
        public Builder totalTimeoutMs(long ms) { this.totalTimeoutMs = ms; return this; }

        /**
         * 设置租户级工具访问策略（allowlist/blocklist），设置后自动注册 ToolAccessHook。
         */
        public Builder toolAccessPolicy(ToolAccessPolicy policy) {
            this.toolAccessPolicy = policy; return this;
        }

        /**
         * 设置系统提示词（注入到每次对话最前面）。
         */
        public Builder systemMessage(String msg) { this.systemMessage = msg; return this; }

        /**
         * 设置长期记忆实现（Redis/MySQL/ReMe等），设置后自动注册 MemoryEnrichmentHook。
         */
        public Builder memory(Memory memory) { this.memory = memory; return this; }

        /**
         * 设置自定义压缩策略（覆盖默认的渐进式压缩）。
         */
        public Builder compactionStrategy(CompactionStrategy strategy) {
            this.compactionStrategy = strategy; return this;
        }

        /**
         * 开启 Plan 模式，自动注册 todo_write 工具。
         */
        public Builder enablePlanMode() { this.enablePlanMode = true; return this; }

        /**
         * 设置敏感词列表，设置后自动注册 ContentFilterHook。
         */
        public Builder contentFilter(List<String> blockedWords) {
            this.contentFilterWords = blockedWords; return this;
        }

        /**
         * 从 AgentExecutionConfig 加载执行配置（已废弃）。
         */
        @Deprecated
        public Builder executionConfig(cd.lan1akea.core.agent.config.AgentExecutionConfig config) {
            if (config != null) {
                this.maxIterations = config.getMaxIterations();
                this.temperature = config.getTemperature();
                this.maxTokens = config.getMaxTokens();
                this.toolChoice = config.getToolChoice();
                this.totalTimeoutMs = config.getTotalTimeoutMs();
            }
            return this;
        }

        /**
         * 添加 MCP 服务器（含 API Key）。
         */
        public Builder mcpServer(String endpoint, String apiKey) {
            this.mcpServers.add(new McpServerConfig(endpoint, apiKey)); return this;
        }
        /**
         * 添加 MCP 服务器（不含 API Key）。
         */
        public Builder mcpServer(String endpoint) { return mcpServer(endpoint, null); }

        /**
         * 构建 HarnessAgent，组装所有组件并初始化。
         */
        public HarnessAgent build() {
            if (name == null || name.isBlank()) throw new AgentConfigurationException("Agent name 不能为空");
            if (model == null) throw new AgentConfigurationException("ChatModel 不能为 null");

            ReActAgent.Builder agentBuilder = ReActAgent.builder().name(name).model(model);
            agentBuilder.toolAdapter(new AnnotationToolAdapter());
            for (Object obj : toolObjects) agentBuilder.tool(obj);

            List<AutoCloseable> mcpClients = new ArrayList<>();
            for (McpServerConfig mcpCfg : mcpServers) {
                McpClient mcpClient = new McpClient(new HttpSseTransport(mcpCfg.endpoint, mcpCfg.apiKey));
                mcpClient.connect().block();
                mcpClients.add(mcpClient);
                mcpClient.listTools().block().forEach(info ->
                    agentBuilder.tool(new McpToolAdapter(mcpClient, info)));
            }

            // === 系统 Hook（默认开启） ===
            agentBuilder.hook(new RateLimitHook());
            agentBuilder.hook(new LoggingHook("HarnessAgent"));

            // === Plan 模式 ===
            if (enablePlanMode) agentBuilder.tool(new TodoWriteTool());

            // === 记忆 ===
            if (memory != null) agentBuilder.hook(new MemoryEnrichmentHook(memory));

            // === 上下文压缩（默认渐进式，从模型获取上下文长度） ===
            int maxInput = model.getMaxInputTokens();
            CompactionStrategy compaction = compactionStrategy != null
                ? compactionStrategy
                : new ProgressiveCompactionStrategy(new ModelContextWindow(name, maxInput, maxInput / 2),
                    new SnipCompactionStrategy(), new TrimCompactionStrategy(),
                    new SummaryCompactionStrategy());
            agentBuilder.hook(new ContextCompressionHook(compaction,
                new ModelContextWindow(name, maxInput, maxInput / 2),
                CompactionContext.builder().maxInputTokens(maxInput).keepRecent(4).build()));

            // === 业务 Hook（按需配置） ===
            if (toolAccessPolicy != null) agentBuilder.hook(new ToolAccessHook(toolAccessPolicy));
            if (contentFilterWords != null) agentBuilder.hook(new ContentFilterHook("ContentFilter", contentFilterWords));

            // === 用户 Hook ===
            for (Hook hook : hooks) agentBuilder.hook(hook);
            for (AroundHook ah : aroundHooks) agentBuilder.aroundHook(ah);
            agentBuilder.stateStore(stateStore != null ? stateStore : new InMemoryAgentStateStore());
            if (maxIterations != null) agentBuilder.maxIterations(maxIterations);
            if (temperature != null) agentBuilder.temperature(temperature);
            if (maxTokens != null) agentBuilder.maxTokens(maxTokens);
            if (toolChoice != null) agentBuilder.toolChoice(toolChoice);
            if (totalTimeoutMs != null) agentBuilder.totalTimeoutMs(totalTimeoutMs);

            ReActAgent agent = agentBuilder.build();
            if (systemMessage != null) agent.setSystemMessage(systemMessage);
            agent.build().block();
            HarnessAgent harness = new HarnessAgent(agent);
            mcpClients.forEach(harness::addCloseable);
            return harness;
        }

        /**
         * MCP 服务器连接配置。
         */
        private record McpServerConfig(
            /**
             * 服务器端点地址。
             */
            String endpoint,
            /**
             * API 密钥。
             */
            String apiKey
        ) {}
    }
}
