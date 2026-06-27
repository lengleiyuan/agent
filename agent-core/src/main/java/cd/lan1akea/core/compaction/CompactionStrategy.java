package cd.lan1akea.core.compaction;

import cd.lan1akea.core.message.Msg;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 上下文压缩策略（SPI 接口）。
 *
 * 框架提供默认范式，业务方可实现自定义策略替换。
 *
 * 范式：
 * 1. shouldCompact()  — 判断是否触发压缩（Token 超过阈值）
 * 2. compact()        — 执行压缩，返回压缩后的消息列表
 */
public interface CompactionStrategy {

    /**
     * 策略名称。
     */
    String getName();

    /**
     * 判断是否需要压缩。
     *
     * @param messages        当前消息列表
     * @param estimatedTokens 估算 Token 数
     * @param maxInputTokens  模型最大输入 Token
     */
    boolean shouldCompact(List<Msg> messages, int estimatedTokens, int maxInputTokens);

    /**
     * 执行压缩。
     *
     * @param messages 当前消息列表
     * @param ctx      压缩上下文（模型、Token 估算器等）
     * @return 压缩后的消息列表
     */
    Mono<List<Msg>> compact(List<Msg> messages, CompactionContext ctx);
}
