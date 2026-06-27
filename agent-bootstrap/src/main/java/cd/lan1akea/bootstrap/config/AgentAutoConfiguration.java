package cd.lan1akea.bootstrap.config;

import cd.lan1akea.core.hook.HookChain;
import cd.lan1akea.core.model.ModelRegistry;
import cd.lan1akea.core.tool.Tool;
import cd.lan1akea.core.tool.ToolRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Agent 自动装配配置。
 * <p>
 * 注意：不再提供 MiddlewareChain（已删除，由 Hook 体系替代）。
 * </p>
 */
@Configuration
public class AgentAutoConfiguration {

    /**
     * 全局工具注册表
     */
    @Bean
    public ToolRegistry toolRegistry(List<Tool> tools) {
        ToolRegistry registry = new ToolRegistry();
        if (tools != null) {
            for (Tool tool : tools) registry.register(tool);
        }
        return registry;
    }

    /**
     * 全局 Hook 链
     */
    @Bean
    public HookChain hookChain() {
        return new HookChain();
    }

    /**
     * 模型注册表
     */
    @Bean
    public ModelRegistry modelRegistry() {
        return new ModelRegistry();
    }
}
