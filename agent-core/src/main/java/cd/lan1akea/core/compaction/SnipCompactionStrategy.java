package cd.lan1akea.core.compaction;

import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.MsgRole;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * 裁剪式压缩 —— 零成本过滤低价值轮次。
 *
 * 不调用 LLM，纯内存操作：
 * - 删除空 tool_result（工具返回空/无意义结果）
 * - 删除重复的连续 system 消息
 * - 删除纯工具调用的 assistant 消息（无文本内容）
 */
public class SnipCompactionStrategy implements CompactionStrategy {

    /**
     * 默认压缩触发阈值（85%）。
     */
    private static final double DEFAULT_THRESHOLD = 0.85;
    /**
     * 策略名称。
     */
    private final String name;
    /**
     * 触发压缩的 Token 使用率阈值。
     */
    private final double threshold;

    /**
     * 使用默认名称和阈值创建 SnipCompactionStrategy。
     */
    public SnipCompactionStrategy() { this("SnipCompaction", DEFAULT_THRESHOLD); }

    /**
     * 创建一个 SnipCompactionStrategy。
     *
     * @param name      策略名称
     * @param threshold 触发压缩的 Token 使用率阈值
     */
    public SnipCompactionStrategy(String name, double threshold) {
        this.name = name;
        this.threshold = threshold;
    }

    /**
     * 返回策略名称。
     */
    @Override public String getName() { return name; }

    /**
     * 当 Token 使用率达到阈值且消息数量大于 4 时返回 true。
     */
    @Override
    public boolean shouldCompact(List<Msg> messages, int estimatedTokens, int maxInputTokens) {
        if (messages.size() <= 4) return false;
        return (double) estimatedTokens / maxInputTokens >= threshold;
    }

    /**
     * 执行裁剪：移除空 tool_result、纯工具调用 assistant 消息和重复 system 消息。
     */
    @Override
    public Mono<List<Msg>> compact(List<Msg> messages, CompactionContext ctx) {
        List<Msg> result = new ArrayList<>(messages.size());
        MsgRole lastRole = null;

        for (Msg msg : messages) {
            // 1. 跳过空 tool_result
            if (msg.getRole() == MsgRole.TOOL) {
                String content = msg.getTextContent();
                if (content == null || content.isBlank() || content.equals("null")
                    || content.startsWith("[错误]")) {
                    continue;
                }
            }

            // 2. 跳过纯工具调用的 assistant（无文本，只有 tool_calls）
            if (msg.getRole() == MsgRole.ASSISTANT && msg.getToolUseBlocks() != null
                && !msg.getToolUseBlocks().isEmpty()) {
                String text = msg.getTextContent();
                if (text == null || text.isBlank()) {
                    continue;
                }
            }

            // 3. 合并连续重复的 system 消息（保留最后一个）
            if (msg.getRole() == MsgRole.SYSTEM && msg.getRole() == lastRole) {
                String content = msg.getTextContent();
                // 简洁地替换前一个 system 消息
                if (!result.isEmpty()) {
                    result.set(result.size() - 1, msg);
                }
                continue;
            }

            result.add(msg);
            lastRole = msg.getRole();
        }

        return Mono.just(result);
    }
}
