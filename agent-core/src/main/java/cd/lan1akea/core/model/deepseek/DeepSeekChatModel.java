package cd.lan1akea.core.model.deepseek;

import cd.lan1akea.core.formatter.DeepSeekMessageFormatter;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.model.transport.HttpClientAdapter;
import cd.lan1akea.core.model.transport.ReactorHttpClientAdapter;
import cd.lan1akea.core.model.transport.SseEventParser;
import cd.lan1akea.core.util.JsonUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * DeepSeek 聊天模型实现。
 * <p>
 * DeepSeek API 与 OpenAI 兼容。
 * </p>
 */
public class DeepSeekChatModel extends ChatModelBase {

    private final String apiKey;
    private final String baseUrl;
    private final HttpClientAdapter httpClient;
    private final SseEventParser sseParser;

    public DeepSeekChatModel(String apiKey, String modelName) {
        this(apiKey, modelName, "https://api.deepseek.com/v1");
    }

    public DeepSeekChatModel(String apiKey, String modelName, String baseUrl) {
        super("deepseek", modelName, new DeepSeekMessageFormatter());
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.httpClient = new ReactorHttpClientAdapter();
        this.sseParser = new SseEventParser();
    }

    @Override
    protected Mono<ChatResponse> doChat(List<Map<String, Object>> formattedMessages,
                                         List<ToolSchema> toolSchemas,
                                         GenerateOptions options) {
        return Mono.fromCallable(() -> buildRequestBody(formattedMessages, toolSchemas, options, false))
            .flatMap(body -> {
                Map<String, String> headers = buildHeaders();
                return httpClient.post(baseUrl + "/chat/completions", headers, body);
            })
            .map(this::parseResponse);
    }

    @Override
    protected Flux<ChatStreamChunk> doStream(List<Map<String, Object>> formattedMessages,
                                              List<ToolSchema> toolSchemas,
                                              GenerateOptions options) {
        return Mono.fromCallable(() -> buildRequestBody(formattedMessages, toolSchemas, options, true))
            .flatMapMany(body -> {
                Map<String, String> headers = buildHeaders();
                return httpClient.postStream(baseUrl + "/chat/completions", headers, body);
            })
            .transform(sseParser::parse);
    }

    private Map<String, String> buildHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        return headers;
    }

    private String buildRequestBody(List<Map<String, Object>> messages,
                                     List<ToolSchema> toolSchemas,
                                     GenerateOptions options, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", getModelName());
        body.put("messages", messages);
        if (options != null) {
            if (options.getTemperature() != null) body.put("temperature", options.getTemperature());
            if (options.getMaxTokens() != null) body.put("max_tokens", options.getMaxTokens());
        }
        if (stream) body.put("stream", true);
        return JsonUtils.toCompactJson(body);
    }

    @SuppressWarnings("unchecked")
    private ChatResponse parseResponse(String json) {
        Map<String, Object> map = JsonUtils.fromJson(json, Map.class);
        List<Map<String, Object>> choices = (List<Map<String, Object>>) map.get("choices");
        Map<String, Object> choice = choices != null && !choices.isEmpty()
            ? choices.get(0) : Collections.emptyMap();
        Map<String, Object> message = (Map<String, Object>) choice.get("message");
        String content = message != null ? (String) message.get("content") : "";
        String finishReason = (String) choice.get("finish_reason");

        cd.lan1akea.core.message.AssistantMessage assistantMsg =
            cd.lan1akea.core.message.AssistantMessage.of(content != null ? content : "");

        Map<String, Object> usage = (Map<String, Object>) map.get("usage");
        int pt = 0, ct = 0;
        if (usage != null) {
            pt = ((Number) usage.getOrDefault("prompt_tokens", 0)).intValue();
            ct = ((Number) usage.getOrDefault("completion_tokens", 0)).intValue();
        }
        return new ChatResponse(assistantMsg, new ChatUsage(pt, ct),
            finishReason, getModelName());
    }
}
