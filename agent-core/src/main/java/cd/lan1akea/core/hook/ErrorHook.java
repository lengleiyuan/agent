package cd.lan1akea.core.hook;

/**
 * 错误 Hook。
 * <p>
 * 在 Agent 执行过程中发生异常时触发，可用于降级处理、告警通知。
 * </p>
 */
public interface ErrorHook extends Hook {
    @Override
    default HookEventType getSubscribedEventType() {
        return HookEventType.ON_ERROR;
    }
}
