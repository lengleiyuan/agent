package cd.lan1akea.core.hook.recorder;

import cd.lan1akea.core.hook.HookEvent;
import cd.lan1akea.core.hook.HookResult;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Hook 记录器。
 * <p>
 * 记录所有 Hook 执行的历史，用于审计、调试回放。
 * </p>
 */
public class HookRecorder {

    /** 记录条目列表 */
    private final List<RecordEntry> entries = new CopyOnWriteArrayList<>();

    /** 是否启用记录 */
    private volatile boolean enabled = true;

    /**
     * 记录一次 Hook 执行。
     *
     * @param hookName Hook 名称
     * @param event    事件
     * @param result   处理结果
     */
    public void record(String hookName, HookEvent event, HookResult result) {
        if (!enabled) return;
        entries.add(new RecordEntry(
            Instant.now().toEpochMilli(),
            hookName,
            event.getEventType(),
            result.getResultType().name()
        ));
    }

    /** @return 所有记录（不可变） */
    public List<RecordEntry> getEntries() {
        return List.copyOf(entries);
    }

    /** 清空记录 */
    public void clear() {
        entries.clear();
    }

    /** 启用/禁用 */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /** @return 记录数 */
    public int size() { return entries.size(); }

    /**
     * 单条记录。
     */
    public static class RecordEntry {
        private final long timestamp;
        private final String hookName;
        private final String eventType;
        private final String resultType;

        RecordEntry(long timestamp, String hookName, String eventType, String resultType) {
            this.timestamp = timestamp;
            this.hookName = hookName;
            this.eventType = eventType;
            this.resultType = resultType;
        }

        public long getTimestamp() { return timestamp; }
        public String getHookName() { return hookName; }
        public String getEventType() { return eventType; }
        public String getResultType() { return resultType; }

        @Override
        public String toString() {
            return String.format("[%d] %s | %s → %s", timestamp, hookName, eventType, resultType);
        }
    }
}
