package cd.lan1akea.core.hook;

/**
 * 摘要 Hook。
 * <p>
 * 在会话摘要生成时触发，可自定义摘要逻辑。
 * </p>
 */
public interface SummaryHook extends Hook {
    @Override
    default HookEventType getSubscribedEventType() {
        return HookEventType.ON_SUMMARY;
    }
}
