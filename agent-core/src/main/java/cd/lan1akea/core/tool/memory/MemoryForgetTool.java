package cd.lan1akea.core.tool.memory;

import cd.lan1akea.core.tool.ToolBase;
import cd.lan1akea.core.tool.ToolCallParam;
import cd.lan1akea.core.tool.ToolResult;
import reactor.core.publisher.Mono;

/**
 * 记忆遗忘工具。
 */
public class MemoryForgetTool extends ToolBase {

    public MemoryForgetTool() {
        declareStringParam("memory_id", "记忆ID", true);
    }

    @Override
    public String getName() { return "memory_forget"; }

    @Override
    public String getDescription() { return "删除指定的记忆条目"; }

    @Override
    public Mono<ToolResult> execute(ToolCallParam params) {
        return Mono.fromCallable(() -> {
            validateParams(params);
            String memoryId = params.getString("memory_id");
            return doForget(memoryId);
        });
    }

    protected ToolResult doForget(String memoryId) {
        return ToolResult.failure("记忆遗忘需要配置 MemoryStore，请覆写 doForget() 方法");
    }
}
