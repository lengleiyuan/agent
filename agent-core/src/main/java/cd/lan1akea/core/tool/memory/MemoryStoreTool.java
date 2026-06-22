package cd.lan1akea.core.tool.memory;

import cd.lan1akea.core.tool.ToolBase;
import cd.lan1akea.core.tool.ToolCallParam;
import cd.lan1akea.core.tool.ToolResult;
import reactor.core.publisher.Mono;

/**
 * 记忆存储工具。
 */
public class MemoryStoreTool extends ToolBase {

    public MemoryStoreTool() {
        declareStringParam("content", "要存储的记忆内容", true);
        declareStringParam("category", "记忆分类", false);
    }

    @Override
    public String getName() { return "memory_store"; }

    @Override
    public String getDescription() { return "将信息存储到长期记忆中"; }

    @Override
    public Mono<ToolResult> execute(ToolCallParam params) {
        return Mono.fromCallable(() -> {
            validateParams(params);
            String content = params.getString("content");
            String category = params.getString("category");
            if (category == null) category = "general";
            return doStore(content, category);
        });
    }

    protected ToolResult doStore(String content, String category) {
        return ToolResult.failure("记忆存储需要配置 MemoryStore，请覆写 doStore() 方法");
    }
}
