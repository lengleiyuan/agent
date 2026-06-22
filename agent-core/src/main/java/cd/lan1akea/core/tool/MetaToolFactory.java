package cd.lan1akea.core.tool;

import cd.lan1akea.core.model.ToolSchema;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 元工具工厂。
 * <p>
 * 创建操作工具的工具（如列出可用工具、获取工具帮助等）。
 * </p>
 */
public class MetaToolFactory {

    private final ToolRegistry registry;

    public MetaToolFactory(ToolRegistry registry) {
        this.registry = registry;
    }

    /**
     * 创建"列出可用工具"工具。
     */
    public Tool createListToolsTool() {
        return new ToolBase() {
            {
                declareStringParam("group", "按分组名过滤（可选）", false);
            }

            @Override
            public String getName() { return "list_tools"; }

            @Override
            public String getDescription() { return "列出当前可用的所有工具及其功能描述"; }

            @Override
            public Mono<ToolResult> execute(ToolCallParam params) {
                String group = params.getString("group");
                List<ToolSchema> schemas;
                if (group != null && !group.isEmpty()) {
                    schemas = registry.getSchemasByGroup(group);
                } else {
                    schemas = registry.getAllSchemas();
                }

                String result = schemas.stream()
                    .map(s -> "- " + s.getName() + ": " + s.getDescription())
                    .collect(Collectors.joining("\n"));

                return Mono.just(ToolResult.success(
                    schemas.isEmpty() ? "没有可用的工具" : "可用工具:\n" + result));
            }
        };
    }

    /**
     * 创建"获取工具帮助"工具。
     */
    public Tool createToolHelpTool() {
        return new ToolBase() {
            {
                declareStringParam("tool_name", "要查询的工具名称", true);
            }

            @Override
            public String getName() { return "tool_help"; }

            @Override
            public String getDescription() { return "获取指定工具的详细参数说明"; }

            @Override
            public Mono<ToolResult> execute(ToolCallParam params) {
                String toolName = params.getString("tool_name");
                Tool tool = registry.get(toolName);
                if (tool == null) {
                    return Mono.just(ToolResult.failure("工具不存在: " + toolName));
                }

                ToolSchema schema = tool.getParameters();
                String help = "工具: " + schema.getName() + "\n"
                    + "描述: " + schema.getDescription() + "\n"
                    + "参数Schema: " + cd.lan1akea.core.util.JsonUtils.toCompactJson(schema.getParametersSchema());

                return Mono.just(ToolResult.success(help));
            }
        };
    }
}
