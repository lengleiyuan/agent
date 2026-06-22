package cd.lan1akea.core.hook;

/**
 * 中断 Hook（人机协同）。
 * <p>
 * 当工具执行抛出 ToolSuspendException 或其他需要人工决策时触发。
 * 实现此接口的 Hook 可以暂停执行并等待外部输入后恢复。
 * </p>
 */
public interface InterruptHook extends Hook {
    @Override
    default HookEventType getSubscribedEventType() {
        return HookEventType.ON_INTERRUPT;
    }
}
