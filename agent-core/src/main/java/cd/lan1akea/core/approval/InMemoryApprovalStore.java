package cd.lan1akea.core.approval;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 基于内存的审批存储实现（测试和开发环境使用）。
 * ConcurrentHashMap 保证线程安全。
 */
public class InMemoryApprovalStore implements ApprovalStore {

    private final ConcurrentHashMap<String, PendingApproval> store = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> approvedKeys = new ConcurrentHashMap<>();

    private static String approvedKey(String sessionId, String toolName) {
        return sessionId + ":" + toolName;
    }

    @Override
    public String createPending(PendingApproval pa) {
        store.put(pa.getApprovalId(), pa);
        return pa.getApprovalId();
    }

    @Override
    public void approve(String approvalId, String approverId, String comment) {
        PendingApproval pa = store.get(approvalId);
        if (pa != null) {
            pa.approve(approverId, comment);
            approvedKeys.put(approvedKey(pa.getSessionId(), pa.getToolName()), approvalId);
        }
    }

    @Override
    public void deny(String approvalId, String approverId, String comment) {
        PendingApproval pa = store.get(approvalId);
        if (pa != null) {
            pa.deny(approverId, comment);
        }
    }

    @Override
    public boolean isApproved(String sessionId, String toolName) {
        String key = approvedKey(sessionId, toolName);
        String approvalId = approvedKeys.get(key);
        if (approvalId == null) return false;
        PendingApproval pa = store.get(approvalId);
        if (pa == null) {
            approvedKeys.remove(key);
            return false;
        }
        if (pa.isExpired()) {
            pa.expire();
            approvedKeys.remove(key);
            return false;
        }
        return pa.getStatus() == PendingApproval.Status.APPROVED;
    }

    @Override
    public void consume(String sessionId, String toolName) {
        approvedKeys.remove(approvedKey(sessionId, toolName));
    }

    @Override
    public List<PendingApproval> getAll() {
        return List.copyOf(store.values());
    }

    @Override
    public List<PendingApproval> getPendingBySession(String sessionId) {
        evictExpired();
        return store.values().stream()
            .filter(pa -> sessionId.equals(pa.getSessionId())
                && pa.getStatus() == PendingApproval.Status.PENDING)
            .collect(Collectors.toList());
    }

    @Override
    public List<PendingApproval> getAllPending() {
        evictExpired();
        return store.values().stream()
            .filter(pa -> pa.getStatus() == PendingApproval.Status.PENDING)
            .collect(Collectors.toList());
    }

    @Override
    public PendingApproval getById(String approvalId) {
        return store.get(approvalId);
    }

    private void evictExpired() {
        for (Map.Entry<String, PendingApproval> e : store.entrySet()) {
            PendingApproval pa = e.getValue();
            if (pa.getStatus() == PendingApproval.Status.PENDING && pa.isExpired()) {
                pa.expire();
                approvedKeys.remove(approvedKey(pa.getSessionId(), pa.getToolName()));
            }
        }
    }
}
