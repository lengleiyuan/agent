package cd.lan1akea.core.tool;

import cd.lan1akea.core.model.ToolSchema;
import reactor.core.publisher.Mono;

/**
 * Schema 占位工具（MCP / Skill 预留）。
 * <p>
 * 预先注册 Schema 给 LLM，实际执行由外部系统提供。
 * 直接调用 execute() 会返回未实现错误。
 * </p>
 */
public class SchemaOnlyTool implements Tool {

    private final String name;
    private final String description;
    private final ToolSchema parameters;

    public SchemaOnlyTool(String name, String description, ToolSchema parameters) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
    }

    @Override
    public String getName() { return name; }

    @Override
    public String getDescription() { return description; }

    @Override
    public ToolSchema getParameters() { return parameters; }

    @Override
    public Mono<ToolResult> execute(ToolCallParam params) {
        return Mono.just(ToolResult.failure(
            "工具 [" + name + "] 尚未接入外部实现，请通过 MCP 或 Skill 协议注册具体实现。"));
    }
}
