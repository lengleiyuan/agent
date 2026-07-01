package cd.lan1akea.core.approval;

import java.util.List;

/**
 * 审批状态存储接口。
 * <p>
 * 业务方实现此接口接入持久化存储（Redis / MySQL）。
 * 框架默认提供 {@link InMemoryApprovalStore} 用于测试和开发。
 */
public interface ApprovalStore {

    /**
     * 创建待审批记录。
     *
     * @param pa 待审批记录
     * @return approvalId
     */
    String createPending(PendingApproval pa);

    /**
     * 批准指定审批项。
     */
    void approve(String approvalId, String approverId, String comment);

    /**
     * 拒绝指定审批项。
     */
    void deny(String approvalId, String approverId, String comment);

    /**
     * 查询指定会话+工具是否已有有效批准（ToolExecutor 调用）。
     */
    boolean isApproved(String sessionId, String toolName);

    /**
     * 消费批准记录（工具执行成功后调用，保证一次性）。
     */
    void consume(String sessionId, String toolName);

    /**
     * 查询全部审批记录（含已处理，审批管理页使用）。
     */
    List<PendingApproval> getAll();

    /**
     * 按会话查询待审批项。
     */
    List<PendingApproval> getPendingBySession(String sessionId);

    /**
     * 查询全部待审批项（审批管理页使用）。
     */
    List<PendingApproval> getAllPending();

    /**
     * 按 ID 查询审批详情。
     */
    PendingApproval getById(String approvalId);
}
