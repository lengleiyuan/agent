package cd.lan1akea.core.tool.builtin;

import cd.lan1akea.core.agent.ReActAgent;
import cd.lan1akea.core.agent.config.AgentConfig;
import cd.lan1akea.core.agent.config.AgentExecutionConfig;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.MsgRole;
import cd.lan1akea.core.message.UserMessage;
import cd.lan1akea.core.model.ChatModel;
import cd.lan1akea.core.model.ToolSchema;
import cd.lan1akea.core.tool.Tool;
import cd.lan1akea.core.tool.ToolCallParam;
import cd.lan1akea.core.tool.ToolRegistry;
import cd.lan1akea.core.tool.ToolResult;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 子 Agent 工具。
 * <p>
 * 将一个 Agent 包装为父 Agent 的工具，实现 Agent-as-Tool 模式。
 * 子 Agent 继承父 Agent 的模型和工具注册表，通过租户/用户/会话上下文隔离。
 * </p>
 *
 * <pre>{@code
 * // 创建子 Agent 工具
 * AgentTool subAgent = new AgentTool("researcher", "研究助手，负责搜索和分析信息",
 *     model, sharedTools);
 *
 * // 注册为父 Agent 的工具
 * toolRegistry.register(subAgent);
 * }</pre>
 */
public class AgentTool implements Tool {

    private final String name;
    private final String description;
    private final ChatModel model;
    private final ToolRegistry toolRegistry;
    private final int maxIterations;
    private final int maxDepth;
    private final ThreadLocal<Integer> depthCounter;

    public AgentTool(String name, String description, ChatModel model, ToolRegistry toolRegistry) {
        this(name, description, model, toolRegistry, 5, 3);
    }

    public AgentTool(String name, String description, ChatModel model,
                     ToolRegistry toolRegistry, int maxIterations, int maxDepth) {
        this.name = name;
        this.description = description;
        this.model = model;
        this.toolRegistry = toolRegistry;
        this.maxIterations = maxIterations;
        this.maxDepth = maxDepth;
        this.depthCounter = new ThreadLocal<>();
    }

    @Override
    public String getName() { return name; }

    @Override
    public String getDescription() { return description; }

    @Override
    public ToolSchema getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("task", Map.of("type", "string", "description", "委托给子Agent的任务描述"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("task"));
        return new ToolSchema(name, description, schema);
    }

    @Override
    public Mono<ToolResult> execute(ToolCallParam params) {
        // 深度控制
        int depth = depthCounter.get() != null ? depthCounter.get() : 0;
        if (depth >= maxDepth) {
            return Mono.just(ToolResult.failure(
                "子Agent调用深度超限: " + depth + "/" + maxDepth));
        }

        String task = params.getString("task");
        if (task == null || task.isBlank()) {
            return Mono.just(ToolResult.failure("task 参数不能为空"));
        }

        AgentConfig config = AgentConfig.builder()
            .name(name)
            .model(model)
            .toolRegistry(toolRegistry)
            .executionConfig(AgentExecutionConfig.builder()
                .maxIterations(maxIterations).build())
            .build();

        ReActAgent subAgent = new ReActAgent(config);

        return subAgent.build()
            .then(Mono.defer(() -> {
                List<Msg> messages = List.of(
                    Msg.builder(MsgRole.SYSTEM)
                        .addText("你是子Agent「" + name + "」，负责: " + description).build(),
                    UserMessage.of(task));

                // 传递父 Agent 的租户/用户/会话上下文
                return subAgent.chat(messages)
                    .contextWrite(ctx -> {
                        if (params.getTenantId() != null)
                            ctx = ctx.put("tenantId", params.getTenantId());
                        if (params.getUserId() != null)
                            ctx = ctx.put("userId", params.getUserId());
                        if (params.getSessionId() != null)
                            ctx = ctx.put("sessionId", params.getSessionId());
                        return ctx;
                    });
            }))
            .map(resp -> ToolResult.success(resp.getMessage().getTextContent()))
            .onErrorResume(e -> Mono.just(ToolResult.failure(
                "子Agent [" + name + "] 执行失败: " + e.getMessage())))
            .doFinally(s -> subAgent.shutdown().subscribe());
    }

    @Override
    public long getTimeoutMs() { return 120_000; }
}
