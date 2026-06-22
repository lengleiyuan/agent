package cd.lan1akea.core.hook;

/**
 * 运行时上下文感知接口。
 * <p>
 * 实现此接口的 Hook 可以在执行前获取当前 Agent 的运行时上下文。
 * </p>
 */
public interface RuntimeContextAware {

    /**
     * 注入运行时上下文。
     *
     * @param context Hook 执行上下文
     */
    void setRuntimeContext(HookContext context);
}
