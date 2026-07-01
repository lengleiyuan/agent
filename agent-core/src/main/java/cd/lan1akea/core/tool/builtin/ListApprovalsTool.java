package cd.lan1akea.core.tool.builtin;

import cd.lan1akea.core.approval.ApprovalStore;
import cd.lan1akea.core.approval.PendingApproval;
import cd.lan1akea.core.tool.ToolBase;
import cd.lan1akea.core.tool.ToolCallContext;
import cd.lan1akea.core.tool.ToolResult;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 查询待审批列表工具。领导 Agent 可用此工具查看所有待处理的审批请求。
 */
public class ListApprovalsTool extends ToolBase {

    private final ApprovalStore approvalStore;

    public ListApprovalsTool(ApprovalStore approvalStore) {
        this.approvalStore = approvalStore;
    }

    @Override
    public String getName() { return "list_approvals"; }

    @Override
    public String getDescription() {
        return "列出所有待审批的操作。返回每条审批的ID、工具名、请求人、参数、问题描述和风险等级";
    }

    @Override
    public Mono<ToolResult> execute(ToolCallContext params) {
        List<PendingApproval> pending = approvalStore.getAllPending();
        if (pending.isEmpty()) {
            return Mono.just(ToolResult.success("当前无待审批项"));
        }
        StringBuilder sb = new StringBuilder();
        sb.append("⚠️ 以下 ").append(pending.size()).append(" 条操作正在等待审批，请勿重新调用这些工具。\n");
        sb.append("如需操作请使用 approve_approval 工具批准或拒绝，或告知用户等待审批人处理。\n\n");
        for (int i = 0; i < pending.size(); i++) {
            PendingApproval a = pending.get(i);
            sb.append("[").append(i + 1).append("] ");
            sb.append("ID: ").append(a.getApprovalId()).append("\n");
            sb.append("    工具: ").append(a.getToolName());
            if (a.getToolDescription() != null) sb.append(" (").append(a.getToolDescription()).append(")");
            sb.append("\n");
            sb.append("    请求人: ").append(a.getRequesterId() != null ? a.getRequesterId() : "—").append("\n");
            sb.append("    风险: ").append(a.getRiskLevel()).append("\n");
            sb.append("    问题: ").append(a.getQuestion()).append("\n");
            if (a.getArguments() != null && !a.getArguments().isEmpty()) {
                sb.append("    参数: ");
                a.getArguments().forEach((k, v) -> sb.append(k).append("=").append(v).append(" "));
                sb.append("\n");
            }
            sb.append("\n");
        }
        return Mono.just(ToolResult.success(sb.toString().trim()));
    }
}
