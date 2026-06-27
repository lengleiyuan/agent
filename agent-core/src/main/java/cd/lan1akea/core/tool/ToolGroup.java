package cd.lan1akea.core.tool;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具组。
 * 命名工具集合，支持按作用域隔离工具可见性。
 */
public class ToolGroup {

    /**
     * 工具组名称
     */
    private final String name;
    /**
     * 工具组作用域
     */
    private final ToolGroupScope scope;
    /**
     * 工具组内的工具列表
     */
    private final List<Tool> tools = new ArrayList<>();

    /**
     * 构造工具组。
     *
     * @param name  工具组名称
     * @param scope 作用域
     */
    public ToolGroup(String name, ToolGroupScope scope) {
        this.name = name;
        this.scope = scope;
    }

    /**
     * 添加工具到组中。
     *
     * @param tool 工具
     */
    public void addTool(Tool tool) {
        tools.add(tool);
    }

    /**
     * 从组中移除指定名称的工具。
     *
     * @param toolName 工具名称
     */
    public void removeTool(String toolName) {
        tools.removeIf(t -> t.getName().equals(toolName));
    }

    /**
     * @return 工具组名称
     */
    public String getName() { return name; }
    /**
     * @return 工具组作用域
     */
    public ToolGroupScope getScope() { return scope; }
    /**
     * @return 工具组内所有工具（副本）
     */
    public List<Tool> getTools() { return new ArrayList<>(tools); }
    /**
     * @return 工具数量
     */
    public int size() { return tools.size(); }
}
