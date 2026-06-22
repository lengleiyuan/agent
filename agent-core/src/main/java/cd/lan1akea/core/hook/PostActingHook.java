package cd.lan1akea.core.hook;

/**
 * 行动执行后 Hook。
 * <p>
 * 在所有工具调用完成后触发。
 * </p>
 */
public interface PostActingHook extends Hook {
    @Override
    default HookEventType getSubscribedEventType() {
        return HookEventType.POST_ACTING;
    }
}
