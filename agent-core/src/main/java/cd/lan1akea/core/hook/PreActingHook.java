package cd.lan1akea.core.hook;

/**
 * 行动执行前 Hook。
 * <p>
 * 在 Agent 汇总所有工具调用并准备执行前触发。
 * </p>
 */
public interface PreActingHook extends Hook {
    @Override
    default HookEventType getSubscribedEventType() {
        return HookEventType.PRE_ACTING;
    }
}
