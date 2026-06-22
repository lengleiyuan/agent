package cd.lan1akea.core.hook;

/**
 * 流式块 Hook。
 * <p>
 * 在流式输出的每个 chunk 到达时触发，可用于实时过滤、高亮、敏感词检测等。
 * </p>
 */
public interface StreamingChunkHook extends Hook {
    @Override
    default HookEventType getSubscribedEventType() {
        return HookEventType.ON_STREAM_CHUNK;
    }
}
