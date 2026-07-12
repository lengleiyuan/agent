package cd.lan1akea.core.hook.impl;

import cd.lan1akea.core.CoreConstants.Usage;
import cd.lan1akea.core.hook.AroundHook;
import cd.lan1akea.core.hook.HookContext;
import cd.lan1akea.core.hook.HookEvent;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.model.ChatStreamChunk;
import cd.lan1akea.core.util.JsonUtils;

import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 流式 Token 估算 Hook。
 *
 * <p>包裹模型推理流，在每个 text chunk 后跟一个估算 usage chunk，
 * 前端可实时显示 token 消耗，无需等到 POST_MODEL 的精确计数。
 *
 * <p>估算逻辑：字符数 / 4 ≈ token 数，仅在被估算值变化时发送。
 * usage chunk type 为 "usage"，payload 同 TokenEstimationHook 格式。
 */
public class StreamTokenEstimationHook implements AroundHook {

    @Override
    public String getName() {
        return "stream-token-estimation";
    }

    @Override
    public Flux<ChatStreamChunk> aroundReasoningStream(HookEvent event, HookContext ctx,
                                                        Function<HookEvent, Flux<ChatStreamChunk>> next) {
        int estimatedPrompt = estimatePromptTokens(event.getMessages());
        int[] totalChars = {0};
        int[] lastEstimated = {0};

        return next.apply(event)
                .concatMap(chunk -> {
                    if (ChatStreamChunk.TYPE_TEXT.equals(chunk.getType())
                            && chunk.getDelta() != null) {
                        totalChars[0] += chunk.getDelta().length();
                        int estimated = Math.max(1, totalChars[0] / 4);
                        if (estimated != lastEstimated[0]) {
                            lastEstimated[0] = estimated;
                            return Flux.just(chunk, buildUsageChunk(estimatedPrompt, estimated));
                        }
                    }
                    return Flux.just(chunk);
                });
    }

    /**
     * 从消息列表估算 prompt token 数。
     */
    private int estimatePromptTokens(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) return 0;
        int chars = 0;
        for (Msg m : messages) {
            String text = m.getTextContent();
            if (text != null) chars += text.length();
        }
        return Math.max(1, chars / 4);
    }

    /**
     * 构建估算 usage chunk。
     */
    private ChatStreamChunk buildUsageChunk(int promptTokens, int completionTokens) {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put(Usage.PROMPT_TOKENS, promptTokens);
        usage.put(Usage.COMPLETION_TOKENS, completionTokens);
        usage.put(Usage.ESTIMATED, true);
        return ChatStreamChunk.builder()
                .delta(JsonUtils.toCompactJson(usage))
                .type(Usage.CHUNK_TYPE)
                .build();
    }
}
