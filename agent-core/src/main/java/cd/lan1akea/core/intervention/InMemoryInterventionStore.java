package cd.lan1akea.core.intervention;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存实现。开发测试用，生产需替换为持久化实现。
 */
public class InMemoryInterventionStore implements InterventionStore {

    private final ConcurrentHashMap<String, InterventionRequest> store = new ConcurrentHashMap<>();

    @Override
    public String create(InterventionRequest req) {
        store.put(req.getInterventionId(), req);
        return req.getInterventionId();
    }

    @Override
    public void approve(String interventionId, String resolverId, String comment) {
        InterventionRequest req = store.get(interventionId);
        if (req != null && req.getStatus() == InterventionRequest.Status.PENDING) {
            req.approve(resolverId, comment);
        }
    }

    @Override
    public void deny(String interventionId, String resolverId, String comment) {
        InterventionRequest req = store.get(interventionId);
        if (req != null && req.getStatus() == InterventionRequest.Status.PENDING) {
            req.deny(resolverId, comment);
        }
    }

    @Override
    public void clarify(String interventionId, String resolverId, String comment,
                        Map<String, Object> modifiedArgs) {
        InterventionRequest req = store.get(interventionId);
        if (req != null && req.getStatus() == InterventionRequest.Status.PENDING) {
            req.clarify(resolverId, comment, modifiedArgs);
        }
    }

    @Override
    public InterventionRequest getById(String interventionId) {
        return store.get(interventionId);
    }

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

    @Override
    public List<InterventionRequest> getAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public void cleanupExpired() {
        for (InterventionRequest req : store.values()) {
            if (req.getStatus() == InterventionRequest.Status.PENDING && req.isExpired()) {
                req.expire();
            }
        }
    }
}
