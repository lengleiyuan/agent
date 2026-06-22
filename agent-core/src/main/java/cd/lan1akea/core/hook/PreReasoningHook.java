package cd.lan1akea.core.hook;

/**
 * LLM 推理前 Hook。
 * <p>
 * 可在 LLM 调用前注入额外上下文、修改系统提示、阻止调用等。
 * </p>
 */
public interface PreReasoningHook extends Hook {
    @Override
    default HookEventType getSubscribedEventType() {
        return HookEventType.PRE_REASONING;
    }
}
