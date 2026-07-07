package cd.lan1akea.bootstrap.config;

import cd.lan1akea.bootstrap.tool.DeleteFileTool;
import cd.lan1akea.bootstrap.tool.SendNotificationTool;
import cd.lan1akea.bootstrap.tool.TransferTool;
import cd.lan1akea.core.intervention.InterventionStore;
import cd.lan1akea.core.intervention.InMemoryInterventionStore;
import cd.lan1akea.core.formatter.OpenAiMessageFormatter;
import cd.lan1akea.core.hook.impl.AuditHook;
import cd.lan1akea.core.hook.impl.ContentFilterHook;
import cd.lan1akea.core.hook.impl.SessionPersistenceHook;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.model.dashscope.DashScopeChatModel;
import cd.lan1akea.core.model.deepseek.DeepSeekChatModel;
import cd.lan1akea.core.model.openai.OpenAIChatModel;
import cd.lan1akea.core.metrics.AgentMetrics;
import cd.lan1akea.core.state.AgentStateStore;
import cd.lan1akea.core.state.InMemoryAgentStateStore;
import cd.lan1akea.core.tool.ToolGroup;
import cd.lan1akea.core.tool.ToolGroupScope;
import cd.lan1akea.core.tool.ToolRegistry;
import cd.lan1akea.core.tool.builtin.CalculatorTool;
import cd.lan1akea.harness.HarnessAgent;
import cd.lan1akea.metrics.MicrometerAgentMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 开发环境默认 Agent 配置。
 * <p>
 * 通过 HarnessAgent.Builder 完整装配所有子系统。
 * API Key 读取优先级：VM 参数 -Dagent.api.key > application.properties 的 agent.api.key
 * </p>
 */
@Configuration
public class DevAgentConfig {

    private static final Logger log = LoggerFactory.getLogger(DevAgentConfig.class);

    @Bean
    public CalculatorTool calculatorTool() {
        return new CalculatorTool();
    }

    @Bean
    public ToolGroup globalToolGroup(ToolRegistry toolRegistry, CalculatorTool calculator) {
        ToolGroup group = new ToolGroup("global-tools", ToolGroupScope.GLOBAL);
        group.addTool(calculator);
        toolRegistry.registerGroup(group);
        return group;
    }

    /**
     * ChatModel Bean — VM 参数或 application.properties 配置真实模型，否则回退到回显模式。
     *
     * <p>VM 参数示例：{@code -Dagent.api.key=sk-xxx -Dagent.api.provider=deepseek}</p>
     * <p>application.properties 示例：{@code agent.api.key=sk-xxx}</p>
     */
    @Bean
    @ConditionalOnMissingBean(ChatModel.class)
    public DynamicChatModel chatModel(Environment env) {
        String apiKey = System.getProperty("agent.api.key",
            env.getProperty("agent.api.key", ""));
        String provider = System.getProperty("agent.api.provider",
            env.getProperty("agent.api.provider", "deepseek"));
        String model = System.getProperty("agent.api.model",
            env.getProperty("agent.api.model", "deepseek-chat"));
        String baseUrl = System.getProperty("agent.api.base-url",
            env.getProperty("agent.api.base-url", ""));

        ChatModel initial;
        if (!apiKey.isBlank()) {
            log.info("使用真实模型: provider={}, model={}", provider, model);
            initial = buildRealModel(apiKey, provider, model, baseUrl);
        } else {
            log.info("未配置 API Key，使用回显演示模式。启动时加 -Dagent.api.key=xxx 接入真实 LLM");
            initial = buildEchoModel();
        }
        return new DynamicChatModel(initial);
    }

    public static ChatModel buildRealModel(String apiKey, String provider, String modelName, String baseUrl) {
        boolean customUrl = !baseUrl.isBlank();
        return switch (provider.toLowerCase()) {
            case "openai" -> customUrl
                ? new OpenAIChatModel(apiKey, modelName, baseUrl)
                : new OpenAIChatModel(apiKey, modelName);
            case "deepseek" -> customUrl
                ? new DeepSeekChatModel(apiKey, modelName, baseUrl)
                : new DeepSeekChatModel(apiKey, modelName);
            case "dashscope" -> customUrl
                ? new DashScopeChatModel(apiKey, modelName, baseUrl)
                : new DashScopeChatModel(apiKey, modelName);
            default -> customUrl
                ? new OpenAIChatModel(apiKey, modelName, baseUrl)
                : new OpenAIChatModel(apiKey, modelName);
        };
    }

    /**
     * 回显模型 — 模拟 ReAct 循环，演示 思考 → 工具调用 → 观察 → 回复 完整流程。
     * <p>
     * 第一轮返回 tool_use 让 ReActLoop 真正执行 CalculatorTool；
     * 第二轮（拿到工具结果后）返回文本回复。
     * </p>
     */
    private ChatModel buildEchoModel() {
        return new ChatModelBase("dev", "echo-model", new OpenAiMessageFormatter()) {
            @Override
            protected Map<String, String> buildAuthHeaders() { return Map.of(); }
            @Override
            protected String buildApiUrl() { return "http://localhost/echo"; }
            @Override
            public int getMaxInputTokens() { return 8192; }

            // ── 非流式 ──
            @Override
            protected Mono<ChatResponse> doChat(List<Map<String, Object>> messages,
                                                 List<ToolSchema> toolSchemas,
                                                 GenerateOptions options) {
                String lastUser = lastUserContent(messages);
                boolean hasToolResult = hasToolResult(messages);

                if (hasToolResult) {
                    String toolContent = lastToolContent(messages);
                    var msg = cd.lan1akea.core.message.AssistantMessage.of(
                        "[回显·ReAct] 问: " + lastUser + "\n工具返回: " + toolContent +
                        "\n---\n配置 -Dagent.api.key=xxx 接入真实 LLM");
                    return Mono.just(new ChatResponse(msg, new ChatUsage(0, 0), "stop", "echo"));
                }

                String expr = extractExpression(messages);
                var msg = cd.lan1akea.core.message.AssistantMessage.builder()
                    .addToolUse("echo_calc", "calculator",
                        "{\"expression\": \"" + expr + "\"}")
                    .build();
                return Mono.just(new ChatResponse(msg, new ChatUsage(0, 0), "tool_calls", "echo"));
            }

            // ── 流式 ──
            @Override
            protected Flux<ChatStreamChunk> doStream(List<Map<String, Object>> messages,
                                                      List<ToolSchema> toolSchemas,
                                                      GenerateOptions options) {
                String lastUser = lastUserContent(messages);
                boolean hasToolResult = hasToolResult(messages);

                if (hasToolResult) {
                    String toolContent = lastToolContent(messages);
                    return Flux.just(
                        ChatStreamChunk.builder().delta("综合工具结果，组织回复...")
                            .type(ChatStreamChunk.TYPE_THINKING).index(0).build(),
                        ChatStreamChunk.builder()
                            .delta("[回显·ReAct]\n问: " + lastUser + "\n工具结果: " + toolContent +
                                   "\n---\n配置 -Dagent.api.key=xxx 接入真实 LLM")
                            .type(ChatStreamChunk.TYPE_TEXT).finishReason("stop").index(1).build()
                    );
                }

                // 第一轮：思考 → 调用 calculator
                String expr = extractExpression(messages);
                return Flux.just(
                    ChatStreamChunk.builder().delta("用户问: " + lastUser + " — 决定使用 calculator 工具")
                        .type(ChatStreamChunk.TYPE_THINKING).index(0).build(),
                    ChatStreamChunk.builder()
                        .delta("calculator").toolName("calculator")
                        .type(ChatStreamChunk.TYPE_TOOL_USE_START).toolUseId("echo_calc").index(1).build(),
                    ChatStreamChunk.builder()
                        .delta("{\"expression\": \"" + expr + "\"}")
                        .type(ChatStreamChunk.TYPE_TOOL_USE_DELTA).toolUseId("echo_calc").index(2).build()
                );
            }

            private String lastUserContent(List<Map<String, Object>> messages) {
                for (int i = messages.size() - 1; i >= 0; i--) {
                    if ("user".equals(messages.get(i).get("role")))
                        return String.valueOf(messages.get(i).getOrDefault("content", ""));
                }
                return "";
            }

            private boolean hasToolResult(List<Map<String, Object>> messages) {
                return messages.stream().anyMatch(m -> "tool".equals(m.get("role")));
            }

            private String lastToolContent(List<Map<String, Object>> messages) {
                for (int i = messages.size() - 1; i >= 0; i--) {
                    Map<String, Object> m = messages.get(i);
                    if ("tool".equals(m.get("role"))) {
                        Object c = m.get("content");
                        if (c instanceof String s) return s;
                        if (c instanceof List<?> list && !list.isEmpty()) {
                            if (list.get(0) instanceof Map<?, ?> block
                                && block.containsKey("content"))
                                return String.valueOf(block.get("content"));
                        }
                        return String.valueOf(c);
                    }
                }
                return "";
            }

            /** 从用户消息中提取数学表达式 */
            private String extractExpression(List<Map<String, Object>> messages) {
                String last = lastUserContent(messages);
                // 尝试匹配常见数学表达式
                java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("[\\d\\s+\\-*/().%^]+").matcher(last);
                if (m.find()) return m.group().trim();
                return "1+1";
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(AgentStateStore.class)
    public AgentStateStore agentStateStore() {
        return new InMemoryAgentStateStore();
    }

    @Bean
    @ConditionalOnMissingBean(InterventionStore.class)
    public InterventionStore interventionStore() {
        return new InMemoryInterventionStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentMetrics agentMetrics(MeterRegistry meterRegistry) {
        log.info("启用 MicrometerAgentMetrics");
        return new MicrometerAgentMetrics(meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean(HarnessAgent.class)
    public HarnessAgent defaultAgent(DynamicChatModel model, AgentStateStore stateStore,
                                      InterventionStore interventionStore, AgentMetrics agentMetrics) {
        log.info("启动 HarnessAgent: DevAgent, model={}:{}", model.getProvider(), model.getModelName());
        return HarnessAgent.builder()
            .name("DevAgent")
            .model(model)
            .metrics(agentMetrics)
            .tool(new CalculatorTool())
            .tool(new TransferTool())
            .tool(new DeleteFileTool())
            .tool(new SendNotificationTool())
            .hook(new ContentFilterHook())
            .hook(new AuditHook("DevAudit"))
            .hook(new SessionPersistenceHook(stateStore))
            .stateStore(stateStore)
            .interventionStore(interventionStore)
            .maxIterations(5)
            .build();
    }
}
