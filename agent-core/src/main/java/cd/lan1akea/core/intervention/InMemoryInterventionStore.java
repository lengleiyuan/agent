package cd.lan1akea.core.intervention;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存实现。开发测试用，生产需替换为持久化实现。
 *
 * <p>基于 {@link ConcurrentHashMap} 存储介入请求，提供线程安全的基本 CRUD 操作。
 * 支持自动过期检查：在查询待处理请求时同步标记已过期的请求。
 */
public class InMemoryInterventionStore implements InterventionStore {

    private final ConcurrentHashMap<String, InterventionRequest> store = new ConcurrentHashMap<>();

    /**
     * 创建介入记录并将其存入内存 Map。
     *
     * @param req 待创建的介入请求
     * @return 介入记录 ID
     */
    @Override
    public String create(InterventionRequest req) {
        store.put(req.getInterventionId(), req);
        return req.getInterventionId();
    }

    /**
     * 批准待处理的介入请求。
     * 仅当请求存在且状态为 PENDING 时生效。
     *
     * @param interventionId 介入记录 ID
     * @param resolverId     处理人 ID
     * @param comment        处理意见
     */
    @Override
    public void approve(String interventionId, String resolverId, String comment) {
        InterventionRequest req = store.get(interventionId);
        if (req != null && req.getStatus() == InterventionRequest.Status.PENDING) {
            req.approve(resolverId, comment);
        }
    }

    /**
     * 拒绝待处理的介入请求。
     * 仅当请求存在且状态为 PENDING 时生效。
     *
     * @param interventionId 介入记录 ID
     * @param resolverId     处理人 ID
     * @param comment        处理意见
     */
    @Override
    public void deny(String interventionId, String resolverId, String comment) {
        InterventionRequest req = store.get(interventionId);
        if (req != null && req.getStatus() == InterventionRequest.Status.PENDING) {
            req.deny(resolverId, comment);
        }
    }

    /**
     * 澄清待处理的介入请求（带修正参数）。
     * 仅当请求存在且状态为 PENDING 时生效。
     *
     * @param interventionId 介入记录 ID
     * @param resolverId     处理人 ID
     * @param comment        处理意见
     * @param modifiedArgs   修正后的工具参数
     */
    @Override
    public void clarify(String interventionId, String resolverId, String comment,
                        Map<String, Object> modifiedArgs) {
        InterventionRequest req = store.get(interventionId);
        if (req != null && req.getStatus() == InterventionRequest.Status.PENDING) {
            req.clarify(resolverId, comment, modifiedArgs);
        }
    }

    /**
     * 按 ID 查询介入请求。
     *
     * @param interventionId 介入记录 ID
     * @return 介入请求，不存在时返回 null
     */
    @Override
    public InterventionRequest getById(String interventionId) {
        return store.get(interventionId);
    }

    /**
     * 获取所有待处理的介入请求。
     * 查询时会同步检查并标记过期请求。
     *
     * @return 待处理介入请求列表
     */
    @Override
    public List<InterventionRequest> getAllPending() {
        List<InterventionRequest> result = new ArrayList<>();
        for (InterventionRequest req : store.values()) {
            if (req.getStatus() == InterventionRequest.Status.PENDING) {
                if (req.isExpired()) req.expire();
                else result.add(req);
            }
        }
        return result;
    }

    /**
     * 按会话查询待处理的介入请求。
     * 查询时会同步检查并标记过期请求。
     *
     * @param sessionId 会话 ID
     * @return 该会话的待处理介入请求列表
     */
    @Override
    public List<InterventionRequest> getPendingBySession(String sessionId) {
        List<InterventionRequest> result = new ArrayList<>();
        for (InterventionRequest req : store.values()) {
            if (sessionId.equals(req.getSessionId())
                    && req.getStatus() == InterventionRequest.Status.PENDING) {
                if (req.isExpired()) req.expire();
                else result.add(req);
            }
        }
        return result;
    }

    /**
     * 获取全部介入记录（含已处理）。
     *
     * @return 全部介入请求列表
     */
    @Override
    public List<InterventionRequest> getAll() {
        return new ArrayList<>(store.values());
    }

    /**
     * 清理过期记录。
     * 遍历所有记录，将 PENDING 且已过期的标记为 EXPIRED。
     */
    @Override
    public void cleanupExpired() {
        for (InterventionRequest req : store.values()) {
            if (req.getStatus() == InterventionRequest.Status.PENDING && req.isExpired()) {
                req.expire();
            }
        }
    }
}
