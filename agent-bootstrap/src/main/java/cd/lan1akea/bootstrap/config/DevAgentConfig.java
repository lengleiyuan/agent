package cd.lan1akea.bootstrap.config;

import cd.lan1akea.core.agent.AbstractAgent;
import cd.lan1akea.core.agent.config.AgentConfig;
import cd.lan1akea.core.agent.config.AgentExecutionConfig;
import cd.lan1akea.core.formatter.OpenAiMessageFormatter;
import cd.lan1akea.core.hook.HookChain;
import cd.lan1akea.core.hook.impl.LoggingHook;
import cd.lan1akea.core.hook.impl.AuditHook;
import cd.lan1akea.core.hook.impl.ContentFilterHook;
import cd.lan1akea.core.hook.impl.RateLimitHook;
import cd.lan1akea.core.middleware.MiddlewareChain;
import cd.lan1akea.core.middleware.LoggingMiddleware;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.session.InMemorySessionStore;
import cd.lan1akea.core.session.SessionStore;
import cd.lan1akea.core.tenant.PermissionEngine;
import cd.lan1akea.core.tenant.PermissionMode;
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
 * 自动装配 Hook 链（日志/审计/内容过滤/频率限制）、中间件、会话存储。
 * 使用回显模型用于开发测试，配置真实 API Key 后自动切换。
 * </p>
 */
@Configuration
public class DevAgentConfig {

    /** 回显模型 */
    @Bean
    @ConditionalOnMissingBean(ChatModel.class)
    public ChatModel echoModel() {
        return new ChatModelBase("dev", "echo-model", new OpenAiMessageFormatter()) {
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

    /** Hook 链（含具体实现） */
    @Bean
    @ConditionalOnMissingBean(HookChain.class)
    public HookChain hookChain() {
        HookChain chain = new HookChain();
        chain.register(new LoggingHook("DevLogger"));
        chain.register(new AuditHook("DevAudit"));
        chain.register(new RateLimitHook(20, 60_000)); // 每分钟最多20次工具调用
        return chain;
    }

    /** 中间件链 */
    @Bean
    @ConditionalOnMissingBean(MiddlewareChain.class)
    public MiddlewareChain middlewareChain() {
        MiddlewareChain chain = new MiddlewareChain();
        chain.register(new LoggingMiddleware());
        return chain;
    }

    /** 会话存储（开发用内存） */
    @Bean
    @ConditionalOnMissingBean(SessionStore.class)
    public SessionStore sessionStore() {
        return new InMemorySessionStore();
    }

    /** 权限引擎（开发用宽松模式） */
    @Bean
    @ConditionalOnMissingBean(PermissionEngine.class)
    public PermissionEngine permissionEngine() {
        return new PermissionEngine(PermissionMode.PERMISSIVE, List.of());
    }

    /** 默认 Agent（完整装配所有子系统） */
    @Bean
    @ConditionalOnMissingBean(HarnessAgent.class)
    public HarnessAgent defaultAgent(ChatModel model, ToolRegistry toolRegistry,
                                      HookChain hookChain, MiddlewareChain middlewareChain,
                                      SessionStore sessionStore, PermissionEngine permissionEngine) {
        // 注册内置工具
        toolRegistry.register(new CalculatorTool());

        // 构建 Agent 配置
        AgentConfig config = AgentConfig.builder()
            .name("DevAgent")
            .model(model)
            .toolRegistry(toolRegistry)
            .hookChain(hookChain)
            .middlewareChain(middlewareChain)
            .sessionStore(sessionStore)
            .executionConfig(AgentExecutionConfig.builder()
                .maxIterations(5)
                .temperature(0.7)
                .maxTokens(2048)
                .build())
            .build();

        // 创建 Agent 并注入子系统
        AbstractAgent inner = new AbstractAgent(config) {
            @Override
            protected Mono<Void> doBuild() { return Mono.empty(); }
        };
        inner.setPermissionEngine(permissionEngine);

        return inner.build().thenReturn(new HarnessAgent(inner)).block();
    }
}
