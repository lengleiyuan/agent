package cd.lan1akea.core.hook;

/**
 * LLM 推理后 Hook。
 * <p>
 * 可在 LLM 返回后检查/修正输出、记录日志等。
 * </p>
 */
public interface PostReasoningHook extends Hook {
    @Override
    default HookEventType getSubscribedEventType() {
        return HookEventType.POST_REASONING;
    }
}
