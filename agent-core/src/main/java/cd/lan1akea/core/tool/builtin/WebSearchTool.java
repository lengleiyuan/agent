package cd.lan1akea.core.tool.builtin;

import cd.lan1akea.core.tool.ToolBase;
import cd.lan1akea.core.tool.ToolCallParam;
import cd.lan1akea.core.tool.ToolResult;
import reactor.core.publisher.Mono;

/**
 * 网络搜索工具（需配置搜索引擎 API）。
 */
public class WebSearchTool extends ToolBase {

    public WebSearchTool() {
        declareStringParam("query", "搜索关键词", true);
        declareNumberParam("max_results", "最大结果数", false);
    }

    @Override
    public String getName() { return "web_search"; }

    @Override
    public String getDescription() { return "搜索互联网获取信息，需要配置搜索引擎 API"; }

    @Override
    public Mono<ToolResult> execute(ToolCallParam params) {
        return Mono.fromCallable(() -> {
            validateParams(params);
            String query = params.getString("query");
            return doSearch(query, 5);
        });
    }

    /** 子类覆写实现具体搜索逻辑 */
    protected ToolResult doSearch(String query, int maxResults) {
        return ToolResult.failure("搜索功能需要配置搜索引擎 API（如 Google/Bing/SerpAPI），请覆写 doSearch() 方法或注入搜索实现");
    }
}
