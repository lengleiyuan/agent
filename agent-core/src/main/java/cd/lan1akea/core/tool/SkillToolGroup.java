package cd.lan1akea.core.tool;

/**
 * Skill 工具组（预留）。
 * <p>
 * 未来 Skill 系统接入后，每个 Skill 会在此组中注册对应的工具。
 * </p>
 */
public class SkillToolGroup extends ToolGroup {

    public SkillToolGroup() {
        super("skill", ToolGroupScope.TENANT);
    }
}
