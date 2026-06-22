package cd.lan1akea.core.tool;

import cd.lan1akea.core.model.ToolSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表（支持租户隔离）。
 * <p>
 * 管理所有已注册的工具实例。分为两层：
 * <ul>
 * <li><b>全局工具</b>：所有租户共享（如 calculator）</li>
 * <li><b>租户工具</b>：每个租户独立注册（如租户自定义的 web_search）</li>
 * </ul>
 * 租户查询时按优先级返回：租户工具覆盖同名全局工具。
 * </p>
 */
public class ToolRegistry {

    /** 全局工具（名称 → 工具实例） */
    private final Map<String, Tool> globalTools = new ConcurrentHashMap<>();

    /** 租户工具（租户ID → 工具名 → 工具实例） */
    private final Map<String, Map<String, Tool>> tenantTools = new ConcurrentHashMap<>();

    // ========================================================================
    // 注册
    // ========================================================================

    /**
     * 注册全局工具。
     */
    public void register(Tool tool) {
        globalTools.put(tool.getName(), tool);
    }

    /**
     * 注册租户专属工具。
     *
     * @param tenantId 租户ID
     * @param tool     工具实例
     */
    public void registerForTenant(String tenantId, Tool tool) {
        tenantTools.computeIfAbsent(tenantId, k -> new ConcurrentHashMap<>())
            .put(tool.getName(), tool);
    }

    /**
     * 批量注册工具。
     */
    public void registerAll(List<Tool> toolList) {
        for (Tool tool : toolList) register(tool);
    }

    /**
     * 注销全局工具。
     */
    public void unregister(String toolName) {
        globalTools.remove(toolName);
    }

    /**
     * 注销租户工具。
     */
    public void unregisterForTenant(String tenantId, String toolName) {
        Map<String, Tool> tt = tenantTools.get(tenantId);
        if (tt != null) tt.remove(toolName);
    }

    // ========================================================================
    // 查询（租户感知）
    // ========================================================================

    /**
     * 获取对指定租户可见的工具 Schema 列表。
     * <p>
     * 合并全局工具 + 租户工具，租户同名工具覆盖全局。
     * </p>
     *
     * @param tenantId 租户ID（null 则只返回全局工具）
     * @return ToolSchema 列表
     */
    public List<ToolSchema> getSchemasForTenant(String tenantId) {
        Map<String, Tool> visible = new ConcurrentHashMap<>();
        // 全局工具作为基础
        globalTools.forEach((name, tool) -> visible.put(name, tool));
        // 租户工具覆盖
        if (tenantId != null) {
            Map<String, Tool> tt = tenantTools.get(tenantId);
            if (tt != null) tt.forEach(visible::put);
        }
        List<ToolSchema> schemas = new ArrayList<>();
        for (Tool tool : visible.values()) {
            schemas.add(tool.getParameters());
        }
        return schemas;
    }

    /**
     * 获取对指定租户可见的所有工具实例。
     */
    public List<Tool> getToolsForTenant(String tenantId) {
        Map<String, Tool> visible = new ConcurrentHashMap<>();
        globalTools.forEach((name, tool) -> visible.put(name, tool));
        if (tenantId != null) {
            Map<String, Tool> tt = tenantTools.get(tenantId);
            if (tt != null) tt.forEach(visible::put);
        }
        return new ArrayList<>(visible.values());
    }

    /**
     * 租户感知的工具查找。
     *
     * @param tenantId 租户ID
     * @param name     工具名称
     * @return Tool 实例，未找到返回 null
     */
    public Tool getForTenant(String tenantId, String name) {
        // 先查租户工具
        if (tenantId != null) {
            Map<String, Tool> tt = tenantTools.get(tenantId);
            if (tt != null) {
                Tool tool = tt.get(name);
                if (tool != null) return tool;
            }
        }
        // 再查全局
        return globalTools.get(name);
    }

    /**
     * 按名称查找全局工具。
     */
    public Tool get(String name) {
        return globalTools.get(name);
    }

    // ========================================================================
    // 向后兼容
    // ========================================================================

    /** @deprecated 使用 getSchemasForTenant */
    public List<ToolSchema> getAllSchemas() {
        return getSchemasForTenant(null);
    }

    /** @deprecated 使用 getSchemasForTenant */
    public List<ToolSchema> getSchemasByGroup(String group) {
        List<ToolSchema> schemas = new ArrayList<>();
        for (Tool tool : globalTools.values()) {
            if (group.equals(tool.getGroup())) {
                schemas.add(tool.getParameters());
            }
        }
        return schemas;
    }

    // ========================================================================
    // 工具信息
    // ========================================================================

    public boolean contains(String name) { return globalTools.containsKey(name); }
    public int size() { return globalTools.size(); }
    public int tenantToolCount(String tenantId) {
        Map<String, Tool> tt = tenantTools.get(tenantId);
        return tt != null ? tt.size() : 0;
    }
    public List<String> getToolNames() { return new ArrayList<>(globalTools.keySet()); }
    public List<Tool> getAllTools() { return new ArrayList<>(globalTools.values()); }
}
