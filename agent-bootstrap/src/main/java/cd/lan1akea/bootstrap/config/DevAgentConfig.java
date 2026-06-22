package cd.lan1akea.bootstrap.config;

import cd.lan1akea.core.agent.Agent;
import cd.lan1akea.core.formatter.OpenAiMessageFormatter;
import cd.lan1akea.core.hook.HookChain;
import cd.lan1akea.core.middleware.MiddlewareChain;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.tool.ToolRegistry;
import cd.lan1akea.harness.HarnessAgent;
import cd.lan1akea.core.tool.builtin.CalculatorTool;
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
 * 当没有显式配置 HarnessAgent Bean 时，创建一个使用回显模型的默认 Agent。
 * </p>
 */
@Configuration
public class DevAgentConfig {

    /** 回显模型：将输入原样返回，用于开发测试 */
    @Bean
    @ConditionalOnMissingBean(ChatModel.class)
    public ChatModel echoModel() {
        return new ChatModelBase("dev", "echo-model", new OpenAiMessageFormatter()) {
            @Override
            protected Mono<ChatResponse> doChat(List<Map<String, Object>> formattedMessages,
                                                 List<ToolSchema> toolSchemas,
                                                 GenerateOptions options) {
                String lastMessage = "";
                if (!formattedMessages.isEmpty()) {
                    Map<String, Object> last = formattedMessages.get(formattedMessages.size() - 1);
                    lastMessage = String.valueOf(last.getOrDefault("content", ""));
                }
                cd.lan1akea.core.message.AssistantMessage msg =
                    cd.lan1akea.core.message.AssistantMessage.of(
                        "[回显模式] 你说: " + lastMessage + "\n配置真实 API Key 后将正常调用 LLM。");
                return Mono.just(new ChatResponse(msg, new ChatUsage(0, 0), "stop", "echo"));
            }

            @Override
            protected Flux<ChatStreamChunk> doStream(List<Map<String, Object>> formattedMessages,
                                                      List<ToolSchema> toolSchemas,
                                                      GenerateOptions options) {
                return Flux.just(ChatStreamChunk.builder()
                    .delta("[回显模式] 流式功能已就绪")
                    .type(ChatStreamChunk.TYPE_TEXT)
                    .build());
            }
        };
    }

    /** 默认 Agent */
    @Bean
    @ConditionalOnMissingBean(HarnessAgent.class)
    public HarnessAgent defaultAgent(ChatModel model, ToolRegistry toolRegistry,
                                      HookChain hookChain, MiddlewareChain middlewareChain) {
        // 注册开发工具
        toolRegistry.register(new CalculatorTool());

        return HarnessAgent.builder()
            .name("DevAgent")
            .model(model)
            .tool(new CalculatorTool())
            .build()
            .block(); // 开发环境可以阻塞
    }
}
