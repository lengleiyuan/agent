package cd.lan1akea.core.tool;

/**
 * SPI: 将任意对象解析为 {@link Tool}。
 * <p>
 * agent-core 不依赖任何注解，通过此接口让 agent-harness 注入注解转换能力。
 * </p>
 */
public interface ToolResolver {

    /**
     * 判断是否能处理该对象。
     */
    boolean canResolve(Object obj);

    /**
     * 将对象解析为 Tool 实例。
     */
    Tool resolve(Object obj);
}
