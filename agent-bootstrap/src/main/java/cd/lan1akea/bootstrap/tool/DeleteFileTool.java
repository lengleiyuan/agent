package cd.lan1akea.bootstrap.tool;

import cd.lan1akea.core.tool.*;
import reactor.core.publisher.Mono;

/**
 * 文件删除工具（演示审批流程）。
 * 始终需要审批，风险等级 CRITICAL。
 */
public class DeleteFileTool extends ToolBase {

    public DeleteFileTool() {
        declareStringParam("path", "文件路径", true);
    }

    @Override
    public String getName() { return "delete_file"; }

    @Override
    public String getDescription() { return "删除指定路径的文件。此操作不可逆，执行前需要审批"; }

    @Override
    public String getRiskLevel() { return "CRITICAL"; }

    @Override
    public Mono<ToolResult> execute(ToolCallContext params) {
        validateParams(params);
        String path = params.getString("path");

        if (!params.isApproved()) {
            throw new ToolSuspendException("delete_file",
                "确认删除文件 " + path + "？此操作不可逆！");
        }

        return Mono.just(ToolResult.success("文件已删除: " + path + " [模拟]"));
    }
}
