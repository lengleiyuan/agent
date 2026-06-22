package cd.lan1akea.core.tool.builtin;

import cd.lan1akea.core.tool.ToolBase;
import cd.lan1akea.core.tool.ToolCallParam;
import cd.lan1akea.core.tool.ToolResult;
import reactor.core.publisher.Mono;

/**
 * 数据库查询工具（需配置数据源）。
 */
public class DatabaseQueryTool extends ToolBase {

    public DatabaseQueryTool() {
        declareStringParam("sql", "SQL查询语句", true);
        declareStringParam("type", "操作类型（SELECT/INSERT/UPDATE/DELETE）", false);
    }

    @Override
    public String getName() { return "database_query"; }

    @Override
    public String getDescription() { return "执行数据库查询，需要人工审批"; }

    @Override
    public boolean requiresApproval() { return true; }

    @Override
    public Mono<ToolResult> execute(ToolCallParam params) {
        return Mono.fromCallable(() -> {
            validateParams(params);
            String sql = params.getString("sql");
            String type = params.getString("type");
            if (type == null) type = "SELECT";

            // 安全：非SELECT需要审批
            if (!"SELECT".equalsIgnoreCase(type.trim())) {
                return ToolResult.failure("仅支持SELECT查询，其他操作需要额外授权");
            }
            return doQuery(sql);
        });
    }

    /** 子类覆写接入实际数据源 */
    protected ToolResult doQuery(String sql) {
        return ToolResult.failure("数据库查询需要配置 DataSource，请覆写 doQuery() 方法");
    }
}
