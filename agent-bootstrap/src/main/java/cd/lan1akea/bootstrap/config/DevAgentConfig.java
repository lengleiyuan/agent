package cd.lan1akea.bootstrap.config;

import cd.lan1akea.core.agent.ReActAgent;
import cd.lan1akea.core.formatter.OpenAiMessageFormatter;
import cd.lan1akea.core.hook.HookChain;
import cd.lan1akea.core.hook.impl.LoggingHook;
import cd.lan1akea.core.hook.impl.AuditHook;
import cd.lan1akea.core.hook.impl.RateLimitHook;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.state.AgentStateStore;
import cd.lan1akea.core.state.InMemoryAgentStateStore;
import cd.lan1akea.core.tool.ToolGroup;
import cd.lan1akea.core.tool.ToolGroupScope;
import cd.lan1akea.core.tool.ToolRegistry;
import cd.lan1akea.core.tool.builtin.CalculatorTool;
import cd.lan1akea.harness.HarnessAgent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 开发环境默认 Agent 配置。
 * <p>
 * 自动装配 Hook 链（日志/审计/内容过滤/频率限制）、状态存储。
 * 使用回显模型用于开发测试，配置真实 API Key 后自动切换。
 * </p>
 */
@Configuration
public class DevAgentConfig {

    /**
     * 回显模型
     */
    @Bean
    @ConditionalOnMissingBean(ChatModel.class)
    public ChatModel echoModel() {
        return new ChatModelBase("dev", "echo-model", new OpenAiMessageFormatter()) {
            @Override
            protected Map<String, String> buildAuthHeaders() { return Map.of(); }
            @Override
            protected String buildApiUrl() { return "http://localhost/echo"; }

            @Override
            protected Mono<ChatResponse> doChat(List<Map<String, Object>> messages,
                                                 List<ToolSchema> toolSchemas,
                                                 GenerateOptions options) {
                String last = "";
                if (!messages.isEmpty()) {
                    last = String.valueOf(messages.get(messages.size() - 1).getOrDefault("content", ""));
                }
                cd.lan1akea.core.message.Msg msg = cd.lan1akea.core.message.AssistantMessage.of(
                    "[回显模式] 你说: " + last + "\n" +
                    "可用工具: calculator\n" +
                    "配置真实 API Key 后将正常调用 LLM。");
                return Mono.just(new ChatResponse(msg, new ChatUsage(0, 0), "stop", "echo"));
            }

            @Override
            protected Flux<ChatStreamChunk> doStream(List<Map<String, Object>> messages,
                                                      List<ToolSchema> toolSchemas,
                                                      GenerateOptions options) {
                return Flux.just(ChatStreamChunk.builder()
                    .delta("[回显模式] 流式功能已就绪")
                    .type(ChatStreamChunk.TYPE_TEXT).build());
            }
        };
    }

    /**
     * Hook 链（含完整预处理链）
     */
    @Bean
    @ConditionalOnMissingBean(HookChain.class)
    public HookChain hookChain() {
        HookChain chain = new HookChain();
        chain.register(new cd.lan1akea.core.hook.impl.ContentFilterHook());
        chain.register(new LoggingHook("DevLogger"));
        chain.register(new AuditHook("DevAudit"));
        chain.register(new RateLimitHook(20, 60_000));
        return chain;
    }

    /**
     * 状态存储（开发用内存）
     */
    @Bean
    @ConditionalOnMissingBean(AgentStateStore.class)
    public AgentStateStore stateStore() {
        return new InMemoryAgentStateStore();
    }

    /**
     * 默认 Agent（完整装配所有子系统）
     */
    @Bean
    @ConditionalOnMissingBean(HarnessAgent.class)
    public HarnessAgent defaultAgent(ChatModel model, ToolRegistry toolRegistry,
                                      AgentStateStore stateStore) {
        // 全局工具组：所有租户可见
        ToolGroup globalGroup = new ToolGroup("global-tools", ToolGroupScope.GLOBAL);
        globalGroup.addTool(new CalculatorTool());
        toolRegistry.registerGroup(globalGroup);

        ReActAgent inner = ReActAgent.builder()
            .name("DevAgent")
            .model(model)
            .toolRegistry(toolRegistry)
            .hooks(
                new cd.lan1akea.core.hook.impl.ContentFilterHook(),
                new LoggingHook("DevLogger"),
                new AuditHook("DevAudit"),
                new RateLimitHook(20, 60_000))
            .maxIterations(5)
            .temperature(0.7)
            .maxTokens(2048)
            .stateStore(stateStore)
            .build();

        inner.build().block();
        return new HarnessAgent(inner);
    }
}
