package cd.lan1akea.core.intervention;

import java.util.List;
import java.util.Map;

/**
 * 人工介入存储接口。替代原有 ApprovalStore，覆盖所有介入类型。
 */
public interface InterventionStore {

    /** 创建介入记录，返回 interventionId */
    String create(InterventionRequest req);

    /** 批准 */
    void approve(String interventionId, String resolverId, String comment);

    /** 拒绝 */
    void deny(String interventionId, String resolverId, String comment);

    /** 澄清（带修正参数） */
    void clarify(String interventionId, String resolverId, String comment,
                 Map<String, Object> modifiedArgs);

    /** 按 ID 查询 */
    InterventionRequest getById(String interventionId);

    /** 所有待处理 */
    List<InterventionRequest> getAllPending();

    /** 按会话查询待处理 */
    List<InterventionRequest> getPendingBySession(String sessionId);

    /** 全部记录（含已处理） */
    List<InterventionRequest> getAll();

    /** 清理过期记录 */
    default void cleanupExpired() {}
}
