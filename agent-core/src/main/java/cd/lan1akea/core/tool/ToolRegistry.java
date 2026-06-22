package cd.lan1akea.core.tool;

import cd.lan1akea.core.model.ToolSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表。
 * <p>
 * 管理所有已注册的工具实例，按名称索引。
 * 提供按组查找、Schema 批量导出等能力。
 * </p>
 */
public class ToolRegistry {

    /** 工具注册表（名称 → 工具实例） */
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    /**
     * 注册工具。
     *
     * @param tool 工具实例
     */
    public void register(Tool tool) {
        tools.put(tool.getName(), tool);
    }

    /**
     * 批量注册工具。
     *
     * @param toolList 工具列表
     */
    public void registerAll(List<Tool> toolList) {
        for (Tool tool : toolList) {
            register(tool);
        }
    }

    /**
     * 注销工具。
     *
     * @param toolName 工具名称
     */
    public void unregister(String toolName) {
        tools.remove(toolName);
    }

    /**
     * 按名称查找工具。
     *
     * @param name 工具名称
     * @return Tool 实例，未找到返回 null
     */
    public Tool get(String name) {
        return tools.get(name);
    }

    /**
     * 获取所有注册工具的 Schema 列表（用于传递给 LLM）。
     *
     * @return ToolSchema 列表
     */
    public List<ToolSchema> getAllSchemas() {
        List<ToolSchema> schemas = new ArrayList<>();
        for (Tool tool : tools.values()) {
            schemas.add(tool.getParameters());
        }
        return schemas;
    }

    /**
     * 获取指定分组的工具 Schema 列表。
     *
     * @param group 分组名称
     * @return ToolSchema 列表
     */
    public List<ToolSchema> getSchemasByGroup(String group) {
        List<ToolSchema> schemas = new ArrayList<>();
        for (Tool tool : tools.values()) {
            if (group.equals(tool.getGroup())) {
                schemas.add(tool.getParameters());
            }
        }
        return schemas;
    }

    /**
     * 判断工具是否存在。
     *
     * @param name 工具名称
     * @return true 如果已注册
     */
    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    /** @return 已注册工具数量 */
    public int size() { return tools.size(); }

    /** @return 所有工具名列表 */
    public List<String> getToolNames() {
        return new ArrayList<>(tools.keySet());
    }

    /** @return 所有工具（不可变） */
    public List<Tool> getAllTools() {
        return new ArrayList<>(tools.values());
    }
}
