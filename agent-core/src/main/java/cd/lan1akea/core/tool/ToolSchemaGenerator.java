package cd.lan1akea.core.tool;

import cd.lan1akea.core.model.ToolSchema;

/**
 * 工具 Schema 生成器（默认实现）。
 * <p>
 * 直接委托给 Tool.getParameters() 获取 Schema。
 * 注解驱动的实现可在 harness 包中覆盖此逻辑。
 * </p>
 */
public class ToolSchemaGenerator implements ToolSchemaProvider {

    @Override
    public ToolSchema provide(Tool tool) {
        return tool.getParameters();
    }
}
