package cd.lan1akea.core.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具组管理器。
 * <p>
 * 管理多个工具组，支持按作用域过滤可见工具。
 * </p>
 */
public class ToolGroupManager {

    private final Map<String, ToolGroup> groups = new ConcurrentHashMap<>();

    /**
     * 创建工具组。
     */
    public ToolGroup createGroup(String name, ToolGroupScope scope) {
        ToolGroup group = new ToolGroup(name, scope);
        groups.put(name, group);
        return group;
    }

    /**
     * 获取工具组。
     */
    public ToolGroup getGroup(String name) {
        return groups.get(name);
    }

    /**
     * 删除工具组。
     */
    public void removeGroup(String name) {
        groups.remove(name);
    }

    /**
     * 获取当前上下文可见的所有工具。
     * <p>
     * 按优先级：SESSION > USER > TENANT > GLOBAL。
     * 同名工具高优先级覆盖低优先级。
     * </p>
     */
    public List<Tool> getVisibleTools() {
        Map<String, Tool> visible = new ConcurrentHashMap<>();

        // 低优先级先放入
        for (ToolGroup group : groups.values()) {
            if (group.getScope() == ToolGroupScope.GLOBAL) {
                for (Tool tool : group.getTools()) {
                    visible.putIfAbsent(tool.getName(), tool);
                }
            }
        }
        for (ToolGroup group : groups.values()) {
            if (group.getScope() == ToolGroupScope.TENANT) {
                for (Tool tool : group.getTools()) {
                    visible.put(tool.getName(), tool); // 覆盖
                }
            }
        }
        for (ToolGroup group : groups.values()) {
            if (group.getScope() == ToolGroupScope.USER) {
                for (Tool tool : group.getTools()) {
                    visible.put(tool.getName(), tool);
                }
            }
        }
        for (ToolGroup group : groups.values()) {
            if (group.getScope() == ToolGroupScope.SESSION) {
                for (Tool tool : group.getTools()) {
                    visible.put(tool.getName(), tool);
                }
            }
        }

        return new ArrayList<>(visible.values());
    }

    /** @return 所有工具组 */
    public List<ToolGroup> getAllGroups() {
        return new ArrayList<>(groups.values());
    }
}
