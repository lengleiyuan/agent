package cd.lan1akea.core.agent;

import cd.lan1akea.core.agent.config.AgentConfig;
import cd.lan1akea.core.agent.config.AgentExecutionConfig;
import cd.lan1akea.core.model.ToolChoicePolicy;
import cd.lan1akea.core.agent.loop.LoopContext;
import cd.lan1akea.core.agent.loop.ReActLoop;
import cd.lan1akea.core.context.RuntimeContext;
import cd.lan1akea.core.exception.AgentConfigurationException;
import cd.lan1akea.core.hook.*;
import cd.lan1akea.core.hook.recorder.HookRecorder;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.SystemMessage;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.model.ChatResponse;
import cd.lan1akea.core.model.ChatStreamChunk;
import cd.lan1akea.core.model.ModelContextWindow;
import cd.lan1akea.core.model.StructuredOutputReminder;
import cd.lan1akea.core.session.*;
import cd.lan1akea.core.state.AgentStateStore;
import cd.lan1akea.core.tool.*;
import cd.lan1akea.core.util.IdGenerator;
import cd.lan1akea.core.util.ValidationUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ReActAgent — 多租户并发安全的 Agent 实现。
 * 构建时注入模型、工具、Hook，每次请求通过 LoopContext 传递可变数据。
 * 无实例级锁，多请求可并发执行。租户/用户/会话上下文通过 RuntimeContext 显式传递。
 */
public class ReActAgent implements StreamableAgent, CallableAgent {

    /**
     * Agent 唯一标识。
     */
    final String id;
    /**
     * Agent 名称。
     */
    final String name;
    /**
     * Agent 配置。
     */
    final AgentConfig config;
    /**
     * 聊天模型。
     */
    final ChatModel model;
    /**
     * 工具注册表。
     */
    final ToolRegistry toolRegistry;
    /**
     * Hook 链。
     */
    final HookChain hookChain;
    /**
     * Hook 分发器。
     */
    final HookDispatcher hookDispatcher;
    /**
     * 工具执行器。
     */
    final ToolExecutor toolExecutor;
    /**
     * ReAct 执行循环。
     */
    final ReActLoop reActLoop;
    /**
     * AroundHook 链。
     */
    final AroundHookChain aroundHookChain;

    /**
     * 状态存储（可选）。
     */
    AgentStateStore stateStore;
    /**
     * 上下文窗口配置。
     */
    ModelContextWindow contextWindow;
    /**
     * Hook 记录器（可选）。
     */
    HookRecorder hookRecorder;
    /**
     * 系统提示消息。
     */
    String systemMessage;

    /**
     * 当前活跃的循环上下文。
     */
    final AtomicReference<LoopContext> activeLoopContext = new AtomicReference<>();
    /**
     * 是否已构建。
     */
    private volatile boolean built;

    /**
     * 使用指定配置创建 ReActAgent。
     *
     * @param config 代理配置，必须包含模型
     */
    public ReActAgent(AgentConfig config) {
        ValidationUtils.notNull(config, "AgentConfig");
        ValidationUtils.notNull(config.getModel(), "ChatModel");

        this.config = config;
        this.id = IdGenerator.nextIdStr();
        this.name = config.getName();
        this.model = config.getModel();

        this.toolRegistry = config.getToolRegistry() != null ? config.getToolRegistry() : new ToolRegistry();
        this.toolExecutor = new ToolExecutor(toolRegistry);

        this.hookChain = config.getHookChain() != null ? config.getHookChain() : new HookChain();
        this.hookDispatcher = new HookDispatcher(this.hookChain);
        this.aroundHookChain = config.getAroundHookChain() != null ? config.getAroundHookChain() : new AroundHookChain();


        this.stateStore = config.getStateStore();
        int maxInput = config.getModel().getMaxInputTokens();
        this.contextWindow = new ModelContextWindow(config.getModel().getModelName(), maxInput, maxInput / 2);

        this.reActLoop = new ReActLoop(model, toolExecutor, hookDispatcher, toolRegistry,
            stateStore, aroundHookChain);
    }



    /**
     * 发送消息并获取回复。
     */
    @Override
    public Mono<ChatResponse> chat(List<Msg> messages) {
        return doChat(messages, null);
    }

    /**
     * 发送消息并获取回复，指定运行时上下文。
     */
    @Override
    public Mono<ChatResponse> chat(List<Msg> messages, RuntimeContext ctx) {
        return doChat(messages, ctx);
    }

    /**
     * 非流式聊天执行入口。
     * RuntimeContext 非 null 时直接使用，否则从 Reactor Context 回退读取。
     * 始终写回 Reactor Context 供下游传播。
     *
     * @param messages 消息列表
     * @param rtCtx 运行时上下文，可为 null
     * @return 聊天响应
     */
    private Mono<ChatResponse> doChat(List<Msg> messages, RuntimeContext rtCtx) {
        ensureBuilt();
        return Mono.deferContextual(ctxView -> {
            final RuntimeContext ctx = resolveRuntimeContext(ctxView, rtCtx);
            String tenantId = ctx.getTenantId();
            String userId = ctx.getUserId();
            String sessId = ctx.getSessionId();

            // aroundCall — 包裹整个 chat（traceId/全链路计时）
            // 上下文压缩由 ContextCompressionHook（PRE_REASONING 优先级5）统一处理
            HookContext callHc = new HookContext(name, tenantId, userId, sessId, 0, List.of(), ctx.getAttributes());
            return aroundHookChain.aroundCall(new HookEvent(null), callHc,
                            e -> loadSessionAndHistory(sessId, messages)
                                    .flatMap(this::injectSystemMessage)
                                    .flatMap(m -> {
                                        LoopContext loopCtx = LoopContext.builder()
                                                .agentName(name).fromRuntimeContext(ctx)
                                                .messages(m).generateOptions(resolveOptions())
                                                .maxIterations(config.getExecutionConfig().getMaxIterations())
                                                .stream(false).build();
                                        activeLoopContext.set(loopCtx);
                                        Mono<ChatResponse> exec = reActLoop.execute(loopCtx)
                                                .doFinally(s -> activeLoopContext.set(null));
                                        long totalTimeout = config.getExecutionConfig().getTotalTimeoutMs();
                                        if (totalTimeout > 0) exec = exec.timeout(Duration.ofMillis(totalTimeout));
                                        return exec;
                                    })
                                    .map(resp -> { e.setPayload("response", resp); return e; }))
                    .map(e -> (ChatResponse) e.getPayload("response"))
                    .contextWrite(c -> writeContext(c, ctx));
        });
    }


    /**
     * 流式发送消息并获取回复。
     */
    @Override
    public Flux<ChatStreamChunk> stream(List<Msg> messages) {
        return doStream(messages, null);
    }

    /**
     * 流式发送消息并获取回复，指定运行时上下文。
     */
    @Override
    public Flux<ChatStreamChunk> stream(List<Msg> messages, RuntimeContext ctx) {
        return doStream(messages, ctx);
    }

    /**
     * 流式聊天执行入口。
     *
     * @param messages 消息列表
     * @param rtCtx 运行时上下文，可为 null
     * @return 聊天流式响应
     */
    private Flux<ChatStreamChunk> doStream(List<Msg> messages, RuntimeContext rtCtx) {
        ensureBuilt();
        return Flux.deferContextual(ctxView -> {
            final RuntimeContext ctx = resolveRuntimeContext(ctxView, rtCtx);
            String sessId = ctx.getSessionId();
            GenerateOptions opts = resolveOptions();

            return loadSessionAndHistory(sessId, messages)
                    .flatMapMany(msgs -> injectSystemMessage(msgs).flatMapMany(Flux::just))
                    .concatMap(m -> {
                        LoopContext loopCtx = LoopContext.builder()
                                .agentName(name)
                                .fromRuntimeContext(ctx)
                                .messages(m).generateOptions(opts)
                                .maxIterations(config.getExecutionConfig().getMaxIterations())
                                .stream(true).build();

                        activeLoopContext.set(loopCtx);
                        return reActLoop.executeStream(loopCtx)
                                .doFinally(s -> activeLoopContext.set(null));
                    })
                    .contextWrite(c -> writeContext(c, ctx));
        });
    }

    /**
     * 发送消息并获取按指定类结构化的回复。
     */
    @Override
    public Mono<ChatResponse> chat(List<Msg> messages, Class<?> outputClass) {
        return chat(StructuredOutputReminder.injectSchemaInstruction(messages, outputClass));
    }

    /**
     * 流式发送消息并获取按指定类结构化的回复。
     */
    @Override
    public Flux<ChatStreamChunk> stream(List<Msg> messages, Class<?> outputClass) {
        return stream(StructuredOutputReminder.injectSchemaInstruction(messages, outputClass));
    }

    /**
     * 创建 Builder。
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        /**
         * Agent 名称。
         */
        private String name;
        /**
         * 聊天模型。
         */
        private ChatModel model;
        /**
         * 工具注册表。
         */
        private final ToolRegistry toolRegistry = new ToolRegistry();
        /**
         * Hook 链。
         */
        private final HookChain hookChain = new HookChain();
        /**
         * AroundHook 链。
         */
        private final AroundHookChain aroundHookChain = new AroundHookChain();
        /**
         * 状态存储（可选）。
         */
        private AgentStateStore stateStore;
        /**
         * 最大迭代次数。
         */
        private Integer maxIterations;
        /**
         * 生成温度参数。
         */
        private Double temperature;
        /**
         * 最大输出 token 数。
         */
        private Integer maxTokens;
        /**
         * 工具选择策略。
         */
        private ToolChoicePolicy toolChoice = ToolChoicePolicy.AUTO;
        /**
         * 总超时毫秒数。
         */
        private long totalTimeoutMs = 300_000;


        /**
         * 设置 Agent 名称。
         */
        public Builder name(String name) { this.name = name; return this; }

        /**
         * 设置模型并读取其默认参数（maxTokens/temperature）。maxIterations 是 Agent 属性不在此列。
         */
        public Builder model(ChatModel model) {
            this.model = model;
            if (this.temperature == null) this.temperature = model.getDefaultTemperature();
            if (this.maxTokens == null) this.maxTokens = model.getDefaultMaxTokens();
            return this;
        }


        /**
         * 注册单个工具（支持 Tool 实例或 ToolFunction 注解类）。
         */
        public Builder tool(Object toolObj) {
            this.toolRegistry.registerTool(toolObj);
            return this;
        }

        /**
         * 注册多个工具。
         */
        public Builder tools(Object... toolObjects) {
            for (Object obj : toolObjects) tool(obj);
            return this;
        }

        /**
         * 注入外部的 ToolRegistry（会合并，后续 .tool() 调用往此 registry 注册）。
         */
        public Builder toolRegistry(ToolRegistry registry) {
            registry.getAllTools().forEach(this.toolRegistry::register);
            return this;
        }

        /**
         * 添加工具适配器（支持 ToolFunction 注解等非 Tool 接口对象）。
         */
        public Builder toolAdapter(ToolAdapter adapter) {
            this.toolRegistry.addAdapter(adapter);
            return this;
        }


        /**
         * 注册单个 Hook。
         */
        public Builder hook(Hook hook) {
            this.hookChain.register(hook);
            return this;
        }

        /**
         * 注册多个 Hook。
         */
        public Builder hooks(Hook... hooks) {
            for (Hook h : hooks) hook(h);
            return this;
        }

        /**
         * 注册 AroundHook。
         */
        public Builder aroundHook(AroundHook hook) {
            this.aroundHookChain.register(hook);
            return this;
        }


        /**
         * 设置最大迭代次数。
         */
        public Builder maxIterations(int n) { this.maxIterations = n; return this; }
        /**
         * 设置生成温度。
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
         * 设置状态存储。
         */
        public Builder stateStore(AgentStateStore store) { this.stateStore = store; return this; }


        /**
         * 构建并返回 ReActAgent 实例。
         *
         * @return 构建完成的 ReActAgent
         * @throws AgentConfigurationException 如果名称或模型未设置
         */
        public ReActAgent build() {
            if (name == null || name.isBlank())
                throw new AgentConfigurationException("Agent name 不能为空");
            if (model == null)
                throw new AgentConfigurationException("ChatModel 不能为 null");

            int iters = maxIterations != null ? maxIterations : 10;
            double temp = temperature != null ? temperature : model.getDefaultTemperature();
            int outTokens = maxTokens != null ? maxTokens : model.getDefaultMaxTokens();

            AgentExecutionConfig execConfig = AgentExecutionConfig.builder()
                .maxIterations(iters)
                .temperature(temp)
                .maxTokens(outTokens)
                .toolChoice(toolChoice)
                .totalTimeoutMs(totalTimeoutMs)
                .build();

            AgentConfig config = AgentConfig.builder()
                .name(name).model(model)
                .toolRegistry(toolRegistry)
                .hookChain(hookChain)
                .aroundHookChain(aroundHookChain)
                .stateStore(stateStore)
                .executionConfig(execConfig)
                .build();

            return new ReActAgent(config);
        }
    }

    /**
     * 构建 Agent，订阅事件并触发初始化。
     *
     * @return 构建完成的 Mono
     */
    public final Mono<Void> build() {
        if (built) return Mono.error(new AgentConfigurationException("Agent [" + name + "] 已构建"));
        built = true;
        return doBuild();
    }

    /**
     * 子类可扩展的构建逻辑。
     *
     * @return 构建完成的 Mono
     */
    protected Mono<Void> doBuild() { return Mono.empty(); }



    /**
     * 解析运行时上下文，优先使用显式传入的，否则从 Reactor Context 回退构建。
     *
     * @param ctxView Reactor 上下文视图
     * @param rtCtx 显式传入的运行时上下文，可为 null
     * @return 解析后的运行时上下文
     */
    private RuntimeContext resolveRuntimeContext(
            reactor.util.context.ContextView ctxView, RuntimeContext rtCtx) {
        if (rtCtx != null) return rtCtx;
        return new RuntimeContext(
            ctxView.getOrDefault("tenantId", null),
            ctxView.getOrDefault("userId", null),
            ctxView.getOrDefault("sessionId", null),
            name,
            ctxView.getOrDefault("attributes", null));
    }

    /**
     * 将运行时上下文写入 Reactor Context 供子 Agent 传播使用。
     *
     * @param c Reactor Context
     * @param ctx 运行时上下文
     * @return 更新后的 Context
     */
    private reactor.util.context.Context writeContext(
            reactor.util.context.Context c, RuntimeContext ctx) {
        if (ctx.getTenantId() != null) c = c.put("tenantId", ctx.getTenantId());
        if (ctx.getUserId() != null) c = c.put("userId", ctx.getUserId());
        if (ctx.getSessionId() != null) c = c.put("sessionId", ctx.getSessionId());
        if (!ctx.getAttributes().isEmpty()) c = c.put("attributes", ctx.getAttributes());
        return c;
    }



    /**
     * 中断当前执行。
     */
    @Override
    public void interrupt() {
        LoopContext ctx = activeLoopContext.get();
        if (ctx != null) ctx.interrupt();
    }

    /**
     * 中断当前执行并附带反馈消息。
     */
    @Override
    public void interrupt(Msg feedbackMsg) {
        LoopContext ctx = activeLoopContext.get();
        if (ctx != null) ctx.interrupt(feedbackMsg);
    }


    /**
     * 关闭 Agent，重置状态。
     *
     * @return 关闭完成的 Mono
     */
    public Mono<Void> shutdown() {
        built = false;
        activeLoopContext.set(null);
        return Mono.empty();
    }

    @Override public String getName() { return name; }
    @Override public String getId() { return id; }
    /**
     * 获取 Agent 配置。
     */
    public AgentConfig getConfig() { return config; }
    /**
     * 获取聊天模型。
     */
    public ChatModel getModel() { return model; }
    /**
     * 获取工具注册表。
     */
    public ToolRegistry getToolRegistry() { return toolRegistry; }
    /**
     * 获取 Hook 链。
     */
    public HookChain getHookChain() { return hookChain; }
    /**
     * 获取事件总线。
     */
    /**
     * 获取状态存储。
     */
    public AgentStateStore getStateStore() { return stateStore; }
    /**
     * 获取上下文窗口。
     */
    public ModelContextWindow getContextWindow() { return contextWindow; }
    /**
     * 获取 Hook 记录器。
     */
    public HookRecorder getHookRecorder() { return hookRecorder; }
    /**
     * 返回是否已构建。
     */
    public boolean isBuilt() { return built; }
    /**
     * 返回是否正在执行。
     */
    public boolean isRunning() { return activeLoopContext.get() != null; }

    /**
     * 加载会话状态和历史消息，合并检查点恢复。
     *
     * @param sessionId 会话 ID
     * @param messages 当前消息
     * @return 合并后的消息列表
     */
    private Mono<List<Msg>> loadSessionAndHistory(String sessionId, List<Msg> messages) {
        if (sessionId == null || stateStore == null) return Mono.just(messages);

        return stateStore.findById(new SessionId(sessionId))
            .flatMap(session -> {
                return stateStore.loadLatestCheckpoint(sessionId)
                    .flatMap(checkpoint -> {
                        if (checkpoint.isShutdownInterrupted()) {
                            checkpoint.setShutdownInterrupted(false);
                            return stateStore.saveCheckpoint(checkpoint)
                                .thenReturn(checkpoint);
                        }
                        return Mono.just(checkpoint);
                    })
                    .flatMap(checkpoint -> {
                        List<Msg> restored = checkpoint.getMessages();
                        if (restored != null && !restored.isEmpty()) {
                            restored.addAll(messages);
                            return Mono.just(restored);
                        }
                        return loadHistory(sessionId, messages);
                    })
                    .switchIfEmpty(loadHistory(sessionId, messages));
            })
            .defaultIfEmpty(messages);
    }

    /**
     * 从状态存储加载会话历史消息。
     *
     * @param sessionId 会话 ID
     * @param messages 当前消息
     * @return 合并历史后的消息列表
     */
    private Mono<List<Msg>> loadHistory(String sessionId, List<Msg> messages) {
        return stateStore.getHistory(new SessionId(sessionId))
            .collectList()
            .map(historyMsgs -> {
                List<Msg> all = new ArrayList<>(historyMsgs);
                all.addAll(messages);
                return all;
            })
            .defaultIfEmpty(messages);
    }

    /**
     * 在消息列表头部注入系统提示消息。
     *
     * @param messages 原始消息列表
     * @return 注入后的消息列表
     */
    private Mono<List<Msg>> injectSystemMessage(List<Msg> messages) {
        String sysMsg = systemMessage;
        if (sysMsg != null && !sysMsg.isBlank()) {
            List<Msg> enriched = new ArrayList<>();
            enriched.add(SystemMessage.of(sysMsg));
            enriched.addAll(messages);
            return Mono.just(enriched);
        }
        return Mono.just(messages);
    }

    // ========================================================================
    // Setter
    // ========================================================================

    public void setStateStore(AgentStateStore v) { this.stateStore = v; }
    public void setSystemMessage(String msg) { this.systemMessage = msg; }
    public void setHookRecorder(HookRecorder v) {
        this.hookRecorder = v;
        if (this.hookDispatcher != null) this.hookDispatcher.setRecorder(v);
    }

    /**
     * 从配置中解析生成选项。
     *
     * @return 生成选项
     */
    private GenerateOptions resolveOptions() {
        AgentExecutionConfig ec = config.getExecutionConfig();
        return GenerateOptions.builder().temperature(ec.getTemperature())
            .maxTokens(ec.getMaxTokens()).toolChoice(ec.getToolChoice()).build();
    }

    /**
     * 检查 Agent 是否已构建，未构建则抛出异常。
     *
     * @throws AgentConfigurationException 如果尚未构建
     */
    private void ensureBuilt() {
        if (!built) throw new AgentConfigurationException("Agent [" + name + "] 尚未构建");
    }
}
