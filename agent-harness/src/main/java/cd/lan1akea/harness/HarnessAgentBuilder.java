package cd.lan1akea.harness;

import cd.lan1akea.core.agent.AbstractAgent;
import cd.lan1akea.core.agent.config.AgentConfig;
import cd.lan1akea.core.agent.config.AgentExecutionConfig;
import cd.lan1akea.core.hook.Hook;
import cd.lan1akea.core.hook.HookChain;
import cd.lan1akea.core.middleware.Middleware;
import cd.lan1akea.core.middleware.MiddlewareChain;
import cd.lan1akea.core.model.ChatModel;
import cd.lan1akea.core.session.SessionStore;
import cd.lan1akea.core.tool.Tool;
import cd.lan1akea.core.tool.ToolRegistry;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * HarnessAgent Builder。
 * <p>
 * 流式 API 构建 Agent 实例。
 * </p>
 */
public class HarnessAgentBuilder {

    private String name;
    private ChatModel model;
    private final List<Tool> tools = new ArrayList<>();
    private final List<Hook> hooks = new ArrayList<>();
    private final List<Middleware> middlewares = new ArrayList<>();
    private SessionStore sessionStore;
    private AgentExecutionConfig executionConfig;

    /** 设置 Agent 名称 */
    public HarnessAgentBuilder name(String name) { this.name = name; return this; }

    /** 设置模型 */
    public HarnessAgentBuilder model(ChatModel model) { this.model = model; return this; }

    /** 注册工具 */
    public HarnessAgentBuilder tool(Tool tool) { this.tools.add(tool); return this; }

    /** 注册 Hook */
    public HarnessAgentBuilder hook(Hook hook) { this.hooks.add(hook); return this; }

    /** 注册中间件 */
    public HarnessAgentBuilder middleware(Middleware middleware) {
        this.middlewares.add(middleware); return this;
    }

    /** 设置会话存储 */
    public HarnessAgentBuilder sessionStore(SessionStore sessionStore) {
        this.sessionStore = sessionStore; return this;
    }

    /** 设置执行配置 */
    public HarnessAgentBuilder executionConfig(AgentExecutionConfig config) {
        this.executionConfig = config; return this;
    }

    /**
     * 构建 HarnessAgent。
     *
     * @return Mono&lt;HarnessAgent&gt;
     */
    public Mono<HarnessAgent> build() {
        // 组装 ToolRegistry
        ToolRegistry toolRegistry = new ToolRegistry();
        for (Tool tool : tools) {
            toolRegistry.register(tool);
        }

        // 组装 HookChain
        HookChain hookChain = new HookChain();
        for (Hook hook : hooks) {
            hookChain.register(hook);
        }

        // 组装 MiddlewareChain
        MiddlewareChain middlewareChain = new MiddlewareChain();
        for (Middleware mw : middlewares) {
            middlewareChain.register(mw);
        }

        // 构建 AgentConfig
        AgentConfig config = AgentConfig.builder()
            .name(name)
            .model(model)
            .toolRegistry(toolRegistry)
            .hookChain(hookChain)
            .middlewareChain(middlewareChain)
            .sessionStore(sessionStore)
            .executionConfig(executionConfig)
            .build();

        // 创建内部 Agent（匿名子类）
        AbstractAgent agent = new AbstractAgent(config) {
            @Override
            protected Mono<Void> doBuild() {
                return Mono.empty();
            }
        };

    return agent.build().thenReturn(new HarnessAgent(agent));
    }
}
