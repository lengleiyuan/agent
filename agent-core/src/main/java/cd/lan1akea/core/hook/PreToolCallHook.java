package cd.lan1akea.core.hook;

/**
 * 工具调用前 Hook。
 * <p>
 * 可在工具执行前校验权限、修改参数、阻止调用。
 * </p>
 */
public interface PreToolCallHook extends Hook {
    @Override
    default HookEventType getSubscribedEventType() {
        return HookEventType.PRE_TOOL_CALL;
    }
}
