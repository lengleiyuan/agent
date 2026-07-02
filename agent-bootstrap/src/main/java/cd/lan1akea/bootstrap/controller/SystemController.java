package cd.lan1akea.bootstrap.controller;

import cd.lan1akea.core.tool.Tool;
import cd.lan1akea.core.tool.ToolGroup;
import cd.lan1akea.core.tool.ToolRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * 系统信息控制器 — 暴露工具注册表、Hook 配置等运行时信息。
 */
@RestController
public class SystemController {

    private final ToolRegistry toolRegistry;

    public SystemController(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * 获取所有已注册的工具组及工具。
     */
    @GetMapping("/api/tools")
    public Mono<Map<String, Object>> tools() {
        List<Map<String, Object>> groups = new ArrayList<>();
        for (ToolGroup group : toolRegistry.getGroups()) {
            List<Map<String, Object>> tools = new ArrayList<>();
            for (Tool tool : group.getTools()) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("name", tool.getName());
                t.put("description", tool.getDescription());
                t.put("riskLevel", tool.getRiskLevel());
                t.put("group", tool.getGroup());
                t.put("timeoutMs", tool.getTimeoutMs());
                t.put("paramsSchema", tool.getParameters().getParametersSchema());
                tools.add(t);
            }
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("name", group.getName());
            g.put("scope", group.getScope().name());
            g.put("tools", tools);
            groups.add(g);
        }
        return Mono.just(Map.of("groups", groups));
    }

    /**
     * 获取当前激活的 Hook 列表。
     */
    @GetMapping("/api/hooks")
    public Mono<Map<String, Object>> hooks() {
        List<Map<String, Object>> list = List.of(
            Map.of(
                "name", "ContentFilterHook",
                "events", List.of("POST_REASONING", "ON_INTERRUPT"),
                "priority", 50,
                "description", "内容安全过滤，拦截敏感词"
            ),
            Map.of(
                "name", "LoggingHook",
                "events", List.of("PRE_REASONING", "POST_REASONING", "PRE_TOOL_CALL", "POST_TOOL_CALL", "ON_ERROR"),
                "priority", 100,
                "description", "全事件日志记录"
            ),
            Map.of(
                "name", "AuditHook",
                "events", List.of("PRE_TOOL_CALL", "POST_TOOL_CALL"),
                "priority", 100,
                "description", "工具调用审计追踪"
            ),
            Map.of(
                "name", "RateLimitHook",
                "events", List.of("PRE_TOOL_CALL"),
                "priority", 10,
                "description", "频率限制：20次/分钟"
            )
        );
        return Mono.just(Map.of("hooks", list, "count", list.size()));
    }
}
