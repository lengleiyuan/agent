package cd.lan1akea.core.intervention;

import java.util.List;
import java.util.Map;

/**
 * 人工介入存储接口。替代原有 ApprovalStore，覆盖所有介入类型。
 *
 * <p>实现类负责介入请求的持久化和查询。
 * 开发环境可使用 {@link InMemoryInterventionStore}，生产环境需替换为数据库实现。
 */
public interface InterventionStore {

    /**
     * 创建介入记录，返回 interventionId。
     * 在 {@code HumanInterventionException} 被 {@code LoopExecutor} 捕获时调用。
     *
     * @param req 待创建的介入请求
     * @return 生成的介入记录 ID
     */
    String create(InterventionRequest req);

    /**
     * 批准介入请求。
     * 通过 {@code ResolveInterventionTool} 操作时调用。
     *
     * @param interventionId 介入记录 ID
     * @param resolverId     处理人 ID
     * @param comment        处理意见
     */
    void approve(String interventionId, String resolverId, String comment);

    /**
     * 拒绝介入请求。
     * 通过 {@code ResolveInterventionTool} 操作时调用。
     *
     * @param interventionId 介入记录 ID
     * @param resolverId     处理人 ID
     * @param comment        处理意见
     */
    void deny(String interventionId, String resolverId, String comment);

    /**
     * 澄清介入请求（带修正参数）。
     * 处理人可以修改原工具参数后重新提交。
     *
     * @param interventionId 介入记录 ID
     * @param resolverId     处理人 ID
     * @param comment        处理意见
     * @param modifiedArgs   修正后的工具参数
     */
    void clarify(String interventionId, String resolverId, String comment,
                 Map<String, Object> modifiedArgs);

    /**
     * 按 ID 查询介入请求。
     * 在 {@code LoopExecutor} 恢复介入和 {@code ResolveInterventionTool} 处理时调用。
     *
     * @param interventionId 介入记录 ID
     * @return 介入请求，不存在时返回 null
     */
    InterventionRequest getById(String interventionId);

    /**
     * 获取所有待处理的介入请求。
     * 通过 {@code ListInterventionsTool} 列出时调用。
     *
     * @return 待处理介入请求列表
     */
    List<InterventionRequest> getAllPending();

    /**
     * 按会话查询待处理的介入请求。
     *
     * @param sessionId 会话 ID
     * @return 该会话的待处理介入请求列表
     */
    List<InterventionRequest> getPendingBySession(String sessionId);

    /**
     * 获取全部介入记录（含已处理）。
     *
     * @return 全部介入请求列表
     */
    List<InterventionRequest> getAll();

    /**
     * 清理过期记录。
     * 由定时任务或后台线程定期调用，将已过期的 PENDING 请求标记为 EXPIRED。
     */
    default void cleanupExpired() {}
}
