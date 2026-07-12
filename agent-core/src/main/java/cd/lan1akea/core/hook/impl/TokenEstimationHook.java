package cd.lan1akea.core.hook.impl;

import cd.lan1akea.core.CoreConstants.EventPayload;
import cd.lan1akea.core.CoreConstants.Usage;
import cd.lan1akea.core.agent.loop.LoopContext;
import cd.lan1akea.core.hook.Hook;
import cd.lan1akea.core.hook.HookContext;
import cd.lan1akea.core.hook.HookEvent;
import cd.lan1akea.core.hook.HookEventType;
import cd.lan1akea.core.hook.HookResult;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.model.ChatResponse;
import cd.lan1akea.core.model.ChatStreamChunk;
import cd.lan1akea.core.model.TokenEstimator;
import cd.lan1akea.core.util.JsonUtils;

import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 模型响应后处理 Hook。
 *
 * <p>挂载在 POST_MODEL 上，执行默认响应处理：
 * <ol>
 *   <li>写入 ctx（lastResponse、tokens、assistant 消息）</li>
 *   <li>token 估算</li>
 *   <li>构建 usage chunk 供前端展示</li>
 * </ol>
 *
 * <p>其它 Hook 可同挂 POST_MODEL 拦截/扩展此行为。
 */
public class TokenEstimationHook implements Hook {

    private final TokenEstimator tokenEstimator;

    /**
     * 构建 TokenEstimationHook。
     *
     * @param tokenEstimator Token 估算器
     */
    public TokenEstimationHook(TokenEstimator tokenEstimator) {
        this.tokenEstimator = tokenEstimator;
    }

    @Override
    public String getName() {
        return "token-estimation";
    }

    @Override
    public Set<HookEventType> getSubscribedEventTypes() {
        return Set.of(HookEventType.POST_MODEL);
    }

    @Override
    public Mono<HookResult> onEvent(HookEvent event, HookContext context) {
        LoopContext loopCtx = event.getPayload(EventPayload.LOOP_CONTEXT);
        ChatResponse resp = event.getPayload(EventPayload.RESPONSE);
        if (loopCtx == null || resp == null) return Mono.just(HookResult.continue_());

        loopCtx.setLastResponse(resp);
        if (resp.getUsage() != null) loopCtx.addTokens(resp.getUsage().getTotalTokens());
        Msg msg = resp.getMessage();
        if (msg != null) loopCtx.addMessage(msg);

        int promptTokens = tokenEstimator.estimate(loopCtx.getMessages());
        int completionTokens = msg != null ? tokenEstimator.estimate(msg) : 0;
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put(Usage.PROMPT_TOKENS, promptTokens);
        usage.put(Usage.COMPLETION_TOKENS, completionTokens);
        ChatStreamChunk usageChunk = ChatStreamChunk.builder()
                .delta(JsonUtils.toCompactJson(usage))
                .type(Usage.CHUNK_TYPE)
                .build();
        event.setPayload(EventPayload.USAGE_CHUNK, usageChunk);
        return Mono.just(HookResult.continue_());
    }
}
