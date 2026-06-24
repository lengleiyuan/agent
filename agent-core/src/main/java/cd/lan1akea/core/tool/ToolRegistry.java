package cd.lan1akea.core.tool;

import cd.lan1akea.core.model.ToolSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表（支持租户隔离 + 工具组 + 多级作用域）。
 * <p>
 * 工具按作用域（{@link ToolGroupScope}）存储于四个级别：
 * <ul>
 * <li><b>GLOBAL</b>：所有租户共享（如 calculator）</li>
 * <li><b>TENANT</b>：租户级隔离，租户间不可见</li>
 * <li><b>USER</b>：用户级隔离，同租户不同用户不可见</li>
 * <li><b>SESSION</b>：会话级隔离，仅当前会话可见</li>
 * </ul>
 * 查询时按优先级 GLOBAL → TENANT → USER → SESSION 合并，后级同名工具覆盖前级。
 * </p>
 */
public class ToolRegistry {

    /** GLOBAL 工具（名称 → 工具实例） */
    private final Map<String, Tool> globalTools = new ConcurrentHashMap<>();

    /** TENANT 工具（租户ID → 工具名 → 工具实例） */
    private final Map<String, Map<String, Tool>> tenantTools = new ConcurrentHashMap<>();

    /** USER 工具（租户ID:用户ID → 工具名 → 工具实例） */
    private final Map<String, Map<String, Tool>> userTools = new ConcurrentHashMap<>();

    /** SESSION 工具（会话ID → 工具名 → 工具实例） */
    private final Map<String, Map<String, Tool>> sessionTools = new ConcurrentHashMap<>();

    /** 已注册的工具组（名称 → ToolGroup） */
    private final Map<String, ToolGroup> groups = new LinkedHashMap<>();

    /** Object→Tool 解析器链（由 agent-harness 注入） */
    private final List<ToolResolver> resolvers = new ArrayList<>();

    // ========================================================================
    // 解析器
    // ========================================================================

    public void addResolver(ToolResolver resolver) {
        resolvers.add(resolver);
    }

    // ========================================================================
    // 工具组注册
    // ========================================================================

    /**
     * 注册工具组，根据其 {@link ToolGroupScope} 自动放置工具到对应级别。
     * <ul>
     * <li>GLOBAL → {@link #register(Tool)}</li>
     * <li>TENANT → {@link #registerForTenant(String, Tool)}，组名作 tenantId</li>
     * <li>USER → 需要 {@link #registerGroup(String, String, ToolGroup)}</li>
     * <li>SESSION → 需要 {@link #registerSessionGroup(String, ToolGroup)}</li>
     * </ul>
     */
    public void registerGroup(ToolGroup group) {
        groups.put(group.getName(), group);
        switch (group.getScope()) {
            case GLOBAL -> {
                for (Tool tool : group.getTools()) register(tool);
            }
            case TENANT -> {
                for (Tool tool : group.getTools()) registerForTenant(group.getName(), tool);
            }
            default -> {
                // USER/SESSION 需要额外上下文，此处无操作，调用方用重载版本
            }
        }
    }

    /**
     * 注册 TENANT 或 USER 级别的工具组。
     *
     * @param tenantId 租户ID（TENANT 或 USER 级别必须）
     * @param userId   用户ID（仅 USER 级别需要，其他传 null）
     * @param group    工具组
     */
    public void registerGroup(String tenantId, String userId, ToolGroup group) {
        groups.put(group.getName(), group);
        switch (group.getScope()) {
            case TENANT -> {
                for (Tool tool : group.getTools()) registerForTenant(tenantId, tool);
            }
            case USER -> {
                for (Tool tool : group.getTools()) registerForUser(tenantId, userId, tool);
            }
            default -> registerGroup(group);
        }
    }

    /**
     * 注册 SESSION 级别的工具组。
     */
    public void registerSessionGroup(String sessionId, ToolGroup group) {
        groups.put(group.getName(), group);
        if (group.getScope() == ToolGroupScope.SESSION) {
            for (Tool tool : group.getTools()) registerForSession(sessionId, tool);
        }
    }

    /** @return 所有已注册的工具组（按注册顺序） */
    public List<ToolGroup> getGroups() {
        return new ArrayList<>(groups.values());
    }

    /** 按名称查找工具组 */
    public ToolGroup getGroup(String name) {
        return groups.get(name);
    }

    // ========================================================================
    // 注册（单工具）
    // ========================================================================

    /** 注册对象（Tool 实例或注解 POJO） */
    public Tool registerTool(Object obj) {
        if (obj instanceof Tool t) { register(t); return t; }
        for (ToolResolver resolver : resolvers) {
            if (resolver.canResolve(obj)) {
                Tool t = resolver.resolve(obj); register(t); return t;
            }
        }
        throw new IllegalArgumentException(
            "无法注册工具 [" + obj.getClass().getName() + "]：不是 Tool 实例，也没有匹配的 ToolResolver");
    }

    /** 注册全局工具 */
    public void register(Tool tool) {
        globalTools.put(tool.getName(), tool);
    }

    /** 注册租户专属工具 */
    public void registerForTenant(String tenantId, Tool tool) {
        tenantTools.computeIfAbsent(tenantId, k -> new ConcurrentHashMap<>())
            .put(tool.getName(), tool);
    }

    /** 注册用户专属工具 */
    public void registerForUser(String tenantId, String userId, Tool tool) {
        String key = userKey(tenantId, userId);
        userTools.computeIfAbsent(key, k -> new ConcurrentHashMap<>())
            .put(tool.getName(), tool);
    }

    /** 注册会话专属工具 */
    public void registerForSession(String sessionId, Tool tool) {
        sessionTools.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
            .put(tool.getName(), tool);
    }

    /** 注册租户专属对象 */
    public Tool registerToolForTenant(String tenantId, Object obj) {
        if (obj instanceof Tool t) { registerForTenant(tenantId, t); return t; }
        for (ToolResolver resolver : resolvers) {
            if (resolver.canResolve(obj)) {
                Tool t = resolver.resolve(obj); registerForTenant(tenantId, t); return t;
            }
        }
        throw new IllegalArgumentException(
            "无法注册工具 [" + obj.getClass().getName() + "]：不是 Tool 实例，也没有匹配的 ToolResolver");
    }

    /** 批量注册全局工具 */
    public void registerAll(List<Tool> toolList) {
        for (Tool tool : toolList) register(tool);
    }

    // ========================================================================
    // 注销
    // ========================================================================

    public void unregister(String toolName) { globalTools.remove(toolName); }

    public void unregisterForTenant(String tenantId, String toolName) {
        Map<String, Tool> tt = tenantTools.get(tenantId);
        if (tt != null) tt.remove(toolName);
    }

    public void unregisterForUser(String tenantId, String userId, String toolName) {
        Map<String, Tool> ut = userTools.get(userKey(tenantId, userId));
        if (ut != null) ut.remove(toolName);
    }

    public void unregisterForSession(String sessionId, String toolName) {
        Map<String, Tool> st = sessionTools.get(sessionId);
        if (st != null) st.remove(toolName);
    }

    // ========================================================================
    // 查询（租户 + 用户 + 会话感知）
    // ========================================================================

    /**
     * 获取对当前上下文可见的工具 Schema。
     * <p>
     * 合并顺序：GLOBAL → TENANT → USER → SESSION。同名工具后级覆盖前级。
     * </p>
     *
     * @param tenantId  租户ID（null 则跳过租户/用户/会话级）
     * @param userId    用户ID（null 则跳过用户级）
     * @param sessionId 会话ID（null 则跳过会话级）
     */
    public List<ToolSchema> getSchemas(String tenantId, String userId, String sessionId) {
        Map<String, Tool> visible = new LinkedHashMap<>();

        // 1. GLOBAL
        globalTools.forEach(visible::put);

        // 2. TENANT
        if (tenantId != null) {
            Map<String, Tool> tt = tenantTools.get(tenantId);
            if (tt != null) tt.forEach(visible::put);
        }

        // 3. USER
        if (tenantId != null && userId != null) {
            Map<String, Tool> ut = userTools.get(userKey(tenantId, userId));
            if (ut != null) ut.forEach(visible::put);
        }

        // 4. SESSION
        if (sessionId != null) {
            Map<String, Tool> st = sessionTools.get(sessionId);
            if (st != null) st.forEach(visible::put);
        }

        List<ToolSchema> schemas = new ArrayList<>();
        for (Tool tool : visible.values()) {
            schemas.add(tool.getParameters());
        }
        return schemas;
    }

    /**
     * 租户级查询（向后兼容）。
     * @deprecated 使用 {@link #getSchemas(String, String, String)}
     */
    @Deprecated
    public List<ToolSchema> getSchemasForTenant(String tenantId) {
        return getSchemas(tenantId, null, null);
    }

    /**
     * 获取可见的工具实例列表。
     */
    public List<Tool> getToolsForTenant(String tenantId) {
        Map<String, Tool> visible = new LinkedHashMap<>();
        globalTools.forEach(visible::put);
        if (tenantId != null) {
            Map<String, Tool> tt = tenantTools.get(tenantId);
            if (tt != null) tt.forEach(visible::put);
        }
        return new ArrayList<>(visible.values());
    }

    /**
     * 租户感知的工具查找（单工具）。
     * @deprecated 使用 {@link #getForContext}
     */
    @Deprecated
    public Tool getForTenant(String tenantId, String name) {
        return getForContext(tenantId, null, null, name);
    }

    /**
     * 全上下文工具查找 — 优先级 SESSION > USER > TENANT > GLOBAL。
     */
    public Tool getForContext(String tenantId, String userId, String sessionId, String name) {
        if (sessionId != null) {
            Map<String, Tool> st = sessionTools.get(sessionId);
            if (st != null) { Tool t = st.get(name); if (t != null) return t; }
        }
        if (tenantId != null && userId != null) {
            Map<String, Tool> ut = userTools.get(userKey(tenantId, userId));
            if (ut != null) { Tool t = ut.get(name); if (t != null) return t; }
        }
        if (tenantId != null) {
            Map<String, Tool> tt = tenantTools.get(tenantId);
            if (tt != null) { Tool t = tt.get(name); if (t != null) return t; }
        }
        return globalTools.get(name);
    }

    public Tool get(String name) {
        return globalTools.get(name);
    }

    // ========================================================================
    // 向后兼容
    // ========================================================================

    /** @deprecated 使用 getSchemasForTenant */
    @Deprecated
    public List<ToolSchema> getAllSchemas() {
        return getSchemasForTenant(null);
    }

    /** @deprecated 使用 getSchemasForTenant */
    @Deprecated
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
    public int userToolCount(String tenantId, String userId) {
        Map<String, Tool> ut = userTools.get(userKey(tenantId, userId));
        return ut != null ? ut.size() : 0;
    }
    public int sessionToolCount(String sessionId) {
        Map<String, Tool> st = sessionTools.get(sessionId);
        return st != null ? st.size() : 0;
    }
    public List<String> getToolNames() { return new ArrayList<>(globalTools.keySet()); }
    public List<Tool> getAllTools() { return new ArrayList<>(globalTools.values()); }

    // ========================================================================
    // 内部
    // ========================================================================

    private static String userKey(String tenantId, String userId) {
        return tenantId + ":" + userId;
    }
}
