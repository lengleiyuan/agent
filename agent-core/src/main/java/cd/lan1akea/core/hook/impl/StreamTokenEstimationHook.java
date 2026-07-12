package cd.lan1akea.core.hook.impl;

import cd.lan1akea.core.CoreConstants.Usage;
import cd.lan1akea.core.hook.AroundHook;
import cd.lan1akea.core.hook.HookContext;
import cd.lan1akea.core.hook.HookEvent;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.SystemMessage;
import cd.lan1akea.core.message.UserMessage;
import cd.lan1akea.core.model.ChatStreamChunk;
import cd.lan1akea.core.model.TokenEstimator;
import cd.lan1akea.core.util.JsonUtils;

import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 流式 Token 估算 Hook。
 *
 * <p>包裹模型推理流，用 {@link TokenEstimator} 实时估算 token 用量，
 * 每个 text chunk 后跟一个估算 usage chunk。前端可实时显示，
 * 无需等到 POST_MODEL 的精确计数。
 */
public class StreamTokenEstimationHook implements AroundHook {

    private final TokenEstimator estimator;

    /**
     * 构建流式 Token 估算 Hook。
     *
     * @param estimator Token 估算器
     */
    public StreamTokenEstimationHook(TokenEstimator estimator) {
        this.estimator = estimator;
    }

    @Override
    public String getName() {
        return "stream-token-estimation";
    }

    @Override
    public Flux<ChatStreamChunk> aroundReasoningStream(HookEvent event, HookContext ctx,
                                                        Function<HookEvent, Flux<ChatStreamChunk>> next) {
        int estimatedPrompt = estimator.estimate(event.getMessages());
        StringBuilder buffer = new StringBuilder();
        int[] lastEstimated = {0};

        return next.apply(event)
                .concatMap(chunk -> {
                    if (ChatStreamChunk.TYPE_TEXT.equals(chunk.getType())
                            && chunk.getDelta() != null) {
                        buffer.append(chunk.getDelta());
                        int estimated = estimator.estimate(UserMessage.of(buffer.toString()));
                        if (estimated != lastEstimated[0]) {
                            lastEstimated[0] = estimated;
                            return Flux.just(chunk, buildUsageChunk(estimatedPrompt, estimated));
                        }
                    }
                    return Flux.just(chunk);
                });
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
