package cd.lan1akea.core.tool;

import cd.lan1akea.core.model.ToolSchema;

/**
 * 工具 Schema 提供者接口。
 * <p>
 * 与 Tool 接口不同的是，此接口关注 Schema 的生成策略，
 * 可以有不同的实现（注解扫描、反射、手动构建等）。
 * </p>
 */
@FunctionalInterface
public interface ToolSchemaProvider {

    /**
     * 为指定工具生成 Schema。
     *
     * @param tool 工具实例
     * @return ToolSchema
     */
    ToolSchema provide(Tool tool);
}
