package cd.lan1akea.core.hook.recorder;

import cd.lan1akea.core.hook.HookEvent;
import cd.lan1akea.core.hook.HookResult;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Hook 记录器。
 *
 * 记录所有 Hook 执行的历史，用于审计、调试回放。
 */
public class HookRecorder {

    /**
     * 记录条目列表
     */
    private final List<RecordEntry> entries = new CopyOnWriteArrayList<>();

    /**
     * 是否启用记录
     */
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

    /**
     * @return 所有记录（不可变）
     */
    public List<RecordEntry> getEntries() {
        return List.copyOf(entries);
    }

    /**
     * 清空所有记录。
     */
    public void clear() {
        entries.clear();
    }

    /**
     * 启用或禁用记录。
     */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /**
     * @return 当前记录数
     */
    public int size() { return entries.size(); }

    /**
     * 单条记录。
     */
    public static class RecordEntry {
        /**
         * 记录时间戳
         */
        private final long timestamp;
        /**
         * Hook 名称
         */
        private final String hookName;
        /**
         * 事件类型
         */
        private final String eventType;
        /**
         * 结果类型
         */
        private final String resultType;

        /**
         * 创建记录条目。
         */
        RecordEntry(long timestamp, String hookName, String eventType, String resultType) {
            this.timestamp = timestamp;
            this.hookName = hookName;
            this.eventType = eventType;
            this.resultType = resultType;
        }

        /**
         * @return 时间戳
         */
        public long getTimestamp() { return timestamp; }
        /**
         * @return Hook 名称
         */
        public String getHookName() { return hookName; }
        /**
         * @return 事件类型
         */
        public String getEventType() { return eventType; }
        /**
         * @return 结果类型
         */
        public String getResultType() { return resultType; }

        /**
         * @return 日志格式的文本
         */
        @Override
        public String toString() {
            return String.format("[%d] %s | %s → %s", timestamp, hookName, eventType, resultType);
        }
    }
}
