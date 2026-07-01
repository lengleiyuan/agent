package cd.lan1akea.harness.store;

import cd.lan1akea.core.approval.ApprovalStore;
import cd.lan1akea.core.approval.PendingApproval;

import java.util.List;

/**
 * Agent 审批存储接口（门面层，可选注入）。
 * public class MysqlApprovalStore implements IApprovalStore {
 *     ...
 * }
 *
 * HarnessAgent.builder()
 *     .approvalStore(new IApprovalStore())
 *     .build();
 * }</pre>
 */
public interface IApprovalStore extends ApprovalStore {

    /**
     * 创建待审批记录。
     *
     * @param pa 待审批记录
     * @return approvalId
     */
    @Override String createPending(PendingApproval pa);

    /**
     * 批准指定审批项。
     */
    @Override void approve(String approvalId, String approverId, String comment);

    /**
     * 拒绝指定审批项。
     */
    @Override void deny(String approvalId, String approverId, String comment);

    /**
     * 查询指定会话+工具是否已有有效批准（ToolExecutor 调用）。
     */
    @Override boolean isApproved(String sessionId, String toolName);

    /**
     * 消费批准记录（工具执行成功后调用，保证一次性）。
     */
    @Override void consume(String sessionId, String toolName);

    /**
     * 查询全部审批记录（含已处理，审批管理页使用）。
     */
    @Override List<PendingApproval> getAll();

    /**
     * 按会话查询待审批项。
     */
    @Override List<PendingApproval> getPendingBySession(String sessionId);

    /**
     * 查询全部待审批项（审批管理页使用）。
     */
    @Override List<PendingApproval> getAllPending();

    /**
     * 按 ID 查询审批详情。
     */
    @Override PendingApproval getById(String approvalId);
}
