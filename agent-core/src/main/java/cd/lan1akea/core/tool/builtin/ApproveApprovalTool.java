package cd.lan1akea.core.tool.builtin;

import cd.lan1akea.core.approval.ApprovalStore;
import cd.lan1akea.core.approval.PendingApproval;
import cd.lan1akea.core.tool.ToolBase;
import cd.lan1akea.core.tool.ToolCallContext;
import cd.lan1akea.core.tool.ToolResult;
import reactor.core.publisher.Mono;

/**
 * 审批操作工具。领导 Agent 可用此工具批准或拒绝待审批请求。
 */
public class ApproveApprovalTool extends ToolBase {

    private final ApprovalStore approvalStore;

    public ApproveApprovalTool(ApprovalStore approvalStore) {
        this.approvalStore = approvalStore;
        declareStringParam("approval_id", "审批记录ID（从 list_approvals 获取）", true);
        declareStringParam("action", "操作：approve（批准）或 deny（拒绝）", true);
        declareStringParam("comment", "审批备注（可选）", false);
    }

    @Override
    public String getName() { return "approve_approval"; }

    @Override
    public String getDescription() {
        return "批准或拒绝一条待审批操作。参数: approval_id(审批ID), action(approve或deny), comment(可选备注)";
    }

    @Override
    public Mono<ToolResult> execute(ToolCallContext params) {
        validateParams(params);
        String approvalId = params.getString("approval_id");
        String action = params.getString("action");
        String comment = params.getString("comment");
        String approver = params.getUserId() != null ? params.getUserId() : "approver";

        PendingApproval pa = approvalStore.getById(approvalId);
        if (pa == null) {
            return Mono.just(ToolResult.failure("审批记录不存在: " + approvalId));
        }
        if (pa.getStatus() != PendingApproval.Status.PENDING) {
            return Mono.just(ToolResult.failure("该审批已处理，当前状态: " + pa.getStatus()));
        }

        if ("approve".equalsIgnoreCase(action)) {
            approvalStore.approve(approvalId, approver, comment != null ? comment : "");
            return Mono.just(ToolResult.success(
                "已批准: " + pa.getToolName() + " — " + pa.getQuestion()));
        } else if ("deny".equalsIgnoreCase(action)) {
            approvalStore.deny(approvalId, approver, comment != null ? comment : "");
            return Mono.just(ToolResult.success(
                "已拒绝: " + pa.getToolName() + " — " + pa.getQuestion()));
        } else {
            return Mono.just(ToolResult.failure("无效操作: " + action + "。请使用 approve 或 deny"));
        }
    }
}
