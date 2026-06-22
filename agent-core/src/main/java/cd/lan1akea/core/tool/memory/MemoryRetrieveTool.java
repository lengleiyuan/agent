package cd.lan1akea.core.tool.memory;

import cd.lan1akea.core.tool.ToolBase;
import cd.lan1akea.core.tool.ToolCallParam;
import cd.lan1akea.core.tool.ToolResult;
import reactor.core.publisher.Mono;

/**
 * 记忆检索工具。
 */
public class MemoryRetrieveTool extends ToolBase {

    public MemoryRetrieveTool() {
        declareStringParam("query", "检索查询", true);
        declareNumberParam("max_results", "最大返回数", false);
    }

    @Override
    public String getName() { return "memory_retrieve"; }

    @Override
    public String getDescription() { return "从长期记忆中检索相关信息"; }

    @Override
    public Mono<ToolResult> execute(ToolCallParam params) {
        return Mono.fromCallable(() -> {
            validateParams(params);
            String query = params.getString("query");
            int maxResults = params.getNumber("max_results") != null
                ? params.getNumber("max_results").intValue() : 10;
            return doRetrieve(query, maxResults);
        });
    }

    protected ToolResult doRetrieve(String query, int maxResults) {
        return ToolResult.failure("记忆检索需要配置 MemoryStore，请覆写 doRetrieve() 方法");
    }
}
