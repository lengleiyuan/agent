package cd.lan1akea.core.tool;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 租户级工具访问策略。
 * 支持 allowlist / blocklist 两种模式：
 * - allowlist 非空 -> 只允许列表内的工具，其余全部拒绝
 * - allowlist 为空 + blocklist 非空 -> 拒绝 blocklist 中的工具，其余放行
 * - 两者都为空 -> 全部放行
 */
public class ToolAccessPolicy {

    /**
     * 租户 allowlist 映射，key 为租户 ID，value 为该租户允许使用的工具名称集合
     */
    private final Map<String, Set<String>> allowlists = new ConcurrentHashMap<>();
    /**
     * 租户 blocklist 映射，key 为租户 ID，value 为该租户禁止使用的工具名称集合
     */
    private final Map<String, Set<String>> blocklists = new ConcurrentHashMap<>();

    /**
     * 设置租户的 allowlist（只允许这些工具）
     */
    public void allow(String tenantId, Set<String> toolNames) {
        allowlists.put(tenantId, Set.copyOf(toolNames));
    }

    /**
     * 设置租户的 blocklist（禁止这些工具）
     */
    public void block(String tenantId, Set<String> toolNames) {
        blocklists.put(tenantId, Set.copyOf(toolNames));
    }

    /**
     * 移除租户的所有策略
     */
    public void remove(String tenantId) {
        allowlists.remove(tenantId);
        blocklists.remove(tenantId);
    }

    /**
     * 判断工具是否允许被调用。
     *
     * @return true = 允许，false = 拒绝
     */
    public boolean isAllowed(String tenantId, String toolName) {
        // 1. allowlist 优先
        Set<String> allow = allowlists.get(tenantId);
        if (allow != null && !allow.isEmpty()) {
            return allow.contains(toolName);
        }
        // 2. blocklist
        Set<String> block = blocklists.get(tenantId);
        if (block != null) {
            return !block.contains(toolName);
        }
        // 3. 默认放行
        return true;
    }

    /**
     * @return 租户的 allowlist（只读），可能为 null
     */
    public Set<String> getAllowlist(String tenantId) {
        Set<String> s = allowlists.get(tenantId);
        return s != null ? s : Collections.emptySet();
    }

    /**
     * @return 租户的 blocklist（只读），可能为 null
     */
    public Set<String> getBlocklist(String tenantId) {
        Set<String> s = blocklists.get(tenantId);
        return s != null ? s : Collections.emptySet();
    }

    /**
     * 清空所有策略
     */
    public void clear() {
        allowlists.clear();
        blocklists.clear();
    }
}
