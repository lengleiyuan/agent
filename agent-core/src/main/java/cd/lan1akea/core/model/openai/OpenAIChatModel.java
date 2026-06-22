package cd.lan1akea.core.model.openai;

import cd.lan1akea.core.formatter.OpenAiMessageFormatter;
import cd.lan1akea.core.model.*;
import cd.lan1akea.core.model.transport.HttpClientAdapter;
import cd.lan1akea.core.model.transport.ReactorHttpClientAdapter;
import cd.lan1akea.core.model.transport.SseEventParser;
import cd.lan1akea.core.util.JsonUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * OpenAI 聊天模型实现。
 * <p>
 * 对接 OpenAI Chat Completions API。
 * </p>
 */
public class OpenAIChatModel extends ChatModelBase {

    private final String apiKey;
    private final String baseUrl;
    private final HttpClientAdapter httpClient;
    private final SseEventParser sseParser;

    public OpenAIChatModel(String apiKey, String modelName) {
        this(apiKey, modelName, "https://api.openai.com/v1");
    }

    public OpenAIChatModel(String apiKey, String modelName, String baseUrl) {
        super("openai", modelName, new OpenAiMessageFormatter());
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
                String url = baseUrl + "/chat/completions";
                return httpClient.post(url, headers, body);
            })
            .map(this::parseChatResponse);
    }

    @Override
    protected Flux<ChatStreamChunk> doStream(List<Map<String, Object>> formattedMessages,
                                              List<ToolSchema> toolSchemas,
                                              GenerateOptions options) {
        return Mono.fromCallable(() -> buildRequestBody(formattedMessages, toolSchemas, options, true))
            .flatMapMany(body -> {
                Map<String, String> headers = buildHeaders();
                String url = baseUrl + "/chat/completions";
                return httpClient.postStream(url, headers, body);
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
                                     GenerateOptions options,
                                     boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", getModelName());
        body.put("messages", messages);

        if (options != null) {
            if (options.getTemperature() != null) {
                body.put("temperature", options.getTemperature());
            }
            if (options.getMaxTokens() != null) {
                body.put("max_tokens", options.getMaxTokens());
            }
            if (options.getTopP() != null) {
                body.put("top_p", options.getTopP());
            }
        }

        if (toolSchemas != null && !toolSchemas.isEmpty()) {
            body.put("tools", buildToolsArray(toolSchemas));
            if (options != null && options.getToolChoice() != null) {
                body.put("tool_choice", convertToolChoice(options.getToolChoice()));
            }
        }

        if (stream) {
            body.put("stream", true);
        }

        return JsonUtils.toCompactJson(body);
    }

    @SuppressWarnings("unchecked")
    private ChatResponse parseChatResponse(String responseJson) {
        Map<String, Object> map = JsonUtils.fromJson(responseJson, Map.class);
        List<Map<String, Object>> choices = (List<Map<String, Object>>) map.get("choices");
        Map<String, Object> choice = choices != null && !choices.isEmpty()
            ? choices.get(0) : Collections.emptyMap();
        Map<String, Object> message = (Map<String, Object>) choice.get("message");

        String content = message != null ? (String) message.get("content") : "";
        String finishReason = (String) choice.get("finish_reason");

        // 构建 AssistantMessage
        cd.lan1akea.core.message.MsgBuilder builder =
            cd.lan1akea.core.message.AssistantMessage.builder()
                .addText(content != null ? content : "");

        // 解析工具调用
        if (message != null) {
            List<Map<String, Object>> toolCalls =
                (List<Map<String, Object>>) message.get("tool_calls");
            if (toolCalls != null) {
                for (Map<String, Object> tc : toolCalls) {
                    String tcId = (String) tc.get("id");
                    Map<String, Object> func = (Map<String, Object>) tc.get("function");
                    if (func != null) {
                        String fName = (String) func.get("name");
                        String fArgs = (String) func.get("arguments");
                        builder.addToolUse(tcId, fName, fArgs != null ? fArgs : "{}");
                    }
                }
            }
        }

        // Token 用量
        Map<String, Object> usage = (Map<String, Object>) map.get("usage");
        int promptTokens = 0, completionTokens = 0;
        if (usage != null) {
            promptTokens = ((Number) usage.getOrDefault("prompt_tokens", 0)).intValue();
            completionTokens = ((Number) usage.getOrDefault("completion_tokens", 0)).intValue();
        }

        cd.lan1akea.core.message.AssistantMessage assistantMsg =
            (cd.lan1akea.core.message.AssistantMessage) builder.build();
        return new ChatResponse(assistantMsg,
            new ChatUsage(promptTokens, completionTokens),
            finishReason, getModelName());
    }

    private List<Map<String, Object>> buildToolsArray(List<ToolSchema> schemas) {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ToolSchema schema : schemas) {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", "function");
            Map<String, Object> func = new LinkedHashMap<>();
            func.put("name", schema.getName());
            func.put("description", schema.getDescription());
            func.put("parameters", schema.getParametersSchema());
            tool.put("function", func);
            tools.add(tool);
        }
        return tools;
    }

    private String convertToolChoice(ToolChoicePolicy policy) {
        switch (policy) {
            case NONE:    return "none";
            case AUTO:    return "auto";
            case REQUIRED: return "required";
            default:      return "auto";
        }
    }
}
