package cd.lan1akea.core.hook.impl;

import cd.lan1akea.core.CoreConstants.ApiFormat;
import cd.lan1akea.core.CoreConstants.Usage;
import cd.lan1akea.core.hook.AroundHook;
import cd.lan1akea.core.hook.HookContext;
import cd.lan1akea.core.hook.HookEvent;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.model.ChatStreamChunk;
import cd.lan1akea.core.model.ChatUsage;
import cd.lan1akea.core.model.TokenEstimator;
import cd.lan1akea.core.model.ToolSchema;
import cd.lan1akea.core.util.JsonUtils;

import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 流式 Token 估算 + 最终精确计费 Hook。
 *
 * <p>包裹模型推理流：
 * <ol>
 *   <li>流式过程中 — 每个 text chunk 后用 {@link TokenEstimator} 估算 usage chunk</li>
 *   <li>流结束时 — 若模型 API 返回了 usage，发送精确 usage chunk；否则用本地估算兜底</li>
 * </ol>
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
        int estimatedPrompt = estimatePrompt(event.getMessages(), event.getToolSchemas());
        StringBuilder buffer = new StringBuilder();
        int[] lastEstimated = {0};

        return next.apply(event)
                .concatMap(chunk -> {
                    if (ChatStreamChunk.TYPE_TEXT.equals(chunk.getType())
                        && chunk.getDelta() != null) {
                        buffer.append(chunk.getDelta());
                        int estimated = estimator.estimate(buffer.toString());
                        if (estimated != lastEstimated[0]) {
                            lastEstimated[0] = estimated;
                            return Flux.just(chunk, buildUsageChunk(estimatedPrompt, estimated, true));
                        }
                    }
                    ChatUsage modelUsage = chunk.getUsage();
                    if (modelUsage != null
                            && (modelUsage.getPromptTokens() > 0 || modelUsage.getCompletionTokens() > 0)) {
                        return Flux.just(chunk,
                                buildUsageChunk(modelUsage.getPromptTokens(), modelUsage.getCompletionTokens(), false));
                    }
                    return Flux.just(chunk);
                });
    }

    /**
     * 估算完整 API 请求体的 token 数，结构与 buildCommonRequestBody 对齐。
     */
    private int estimatePrompt(List<Msg> messages, List<ToolSchema> schemas) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(ApiFormat.MESSAGES, buildMessageArray(messages));
        if (schemas != null && !schemas.isEmpty()) {
            body.put(ApiFormat.TOOLS, buildToolArray(schemas));
            body.put(ApiFormat.TOOL_CHOICE, ApiFormat.TOOL_CHOICE_AUTO);
        }
        body.put(ApiFormat.STREAM, true);
        return estimator.estimate(JsonUtils.toCompactJson(body));
    }

    /**
     * 构建消息 JSON 数组（模拟 API 格式）。
     */
    private List<Map<String, Object>> buildMessageArray(List<Msg> messages) {
        List<Map<String, Object>> arr = new ArrayList<>();
        if (messages == null) return arr;
        for (Msg m : messages) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put(ApiFormat.ROLE, m.getRole().name().toLowerCase());
            msg.put(ApiFormat.CONTENT, m.getTextContent());
            arr.add(msg);
        }
        return arr;
    }

    /**
     * 构建工具 Schema JSON 数组，与 API buildToolArray 一致。
     */
    private List<Map<String, Object>> buildToolArray(List<ToolSchema> schemas) {
        List<Map<String, Object>> tools = new ArrayList<>();
        if (schemas == null) return tools;
        for (ToolSchema s : schemas) {
            Map<String, Object> func = new LinkedHashMap<>();
            func.put(ApiFormat.NAME, s.getName());
            func.put(ApiFormat.DESCRIPTION, s.getDescription());
            func.put(ApiFormat.PARAMETERS, s.getParametersSchema());
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put(ApiFormat.TYPE, ApiFormat.TYPE_FUNCTION);
            tool.put(ApiFormat.FUNCTION, func);
            tools.add(tool);
        }
        return tools;
    }

    /**
     * 构建 usage chunk。
     */
    private ChatStreamChunk buildUsageChunk(int promptTokens, int completionTokens, boolean estimated) {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put(Usage.PROMPT_TOKENS, promptTokens);
        usage.put(Usage.COMPLETION_TOKENS, completionTokens);
        if (estimated) usage.put(Usage.ESTIMATED, true);
        return ChatStreamChunk.builder()
                .delta(JsonUtils.toCompactJson(usage))
                .type(Usage.CHUNK_TYPE)
                .build();
    }
}
