package cd.lan1akea.core.hook;

/**
 * 工具调用后 Hook。
 * <p>
 * 可在工具执行后审查结果、补充信息。
 * </p>
 */
public interface PostToolCallHook extends Hook {
    @Override
    default HookEventType getSubscribedEventType() {
        return HookEventType.POST_TOOL_CALL;
    }
}
