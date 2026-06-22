package cd.lan1akea.core.agent.loop;

/**
 * ReAct 循环单步抽象。
 * <p>
 * Reasoning、Acting、Observation 都实现此接口。
 * </p>
 */
public interface LoopStep {

    /**
     * @return 步骤名称（用于日志）
     */
    String getStepName();
}
