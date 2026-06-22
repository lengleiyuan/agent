package cd.lan1akea.core.exception;

/**
 * Hook 中止异常，在 Hook 决定终止 Agent 执行时抛出。
 */
public class HookAbortException extends AgentException {

    private final String hookName;

    public HookAbortException(String hookName, String reason) {
        super("HK_001", "Hook [" + hookName + "] 终止了执行: " + reason);
        this.hookName = hookName;
    }

    /** @return 触发中止的Hook名称 */
    public String getHookName() { return hookName; }
}
