package cd.lan1akea.core.tool;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具组。
 * <p>
 * 命名工具集合，支持按作用域隔离工具可见性。
 * </p>
 */
public class ToolGroup {

    private final String name;
    private final ToolGroupScope scope;
    private final List<Tool> tools = new ArrayList<>();

    public ToolGroup(String name, ToolGroupScope scope) {
        this.name = name;
        this.scope = scope;
    }

    public void addTool(Tool tool) {
        tools.add(tool);
    }

    public void removeTool(String toolName) {
        tools.removeIf(t -> t.getName().equals(toolName));
    }

    public String getName() { return name; }
    public ToolGroupScope getScope() { return scope; }
    public List<Tool> getTools() { return new ArrayList<>(tools); }
    public int size() { return tools.size(); }
}
