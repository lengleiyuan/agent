package cd.lan1akea.core.model;

import cd.lan1akea.core.exception.ModelCallException;
import cd.lan1akea.core.formatter.MessageFormatter;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.MsgBuilder;
import cd.lan1akea.core.message.MsgRole;
import cd.lan1akea.core.message.AssistantMessage;
import cd.lan1akea.core.model.transport.HttpClientAdapter;
import cd.lan1akea.core.model.transport.ReactorHttpClientAdapter;
import cd.lan1akea.core.model.transport.SseEventParser;
import cd.lan1akea.core.util.JsonUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * 聊天模型抽象基类。
 * <p>
 * 提供消息格式化、请求构建、工具调用解析、重试等共享逻辑。
 * 子类只需实现 {@link #buildAuthHeaders()} 和 {@link #buildApiUrl()}。
 * </p>
 */
public abstract class ChatModelBase implements ChatModel {

    private final String provider;
    private final String modelName;
    private final MessageFormatter formatter;

    /** HTTP 客户端 */
    protected final HttpClientAdapter httpClient;

    /** SSE 解析器 */
    protected final SseEventParser sseParser;

    private int maxRetries = 3;
    private long retryDelayMs = 1000;

    protected ChatModelBase(String provider, String modelName, MessageFormatter formatter) {
        this.provider = provider;
        this.modelName = modelName;
        this.formatter = formatter;
        this.httpClient = new ReactorHttpClientAdapter();
        this.sseParser = new SseEventParser();
    }

    // ========================================================================
    // 子类必须实现
    // ========================================================================

    /** @return 认证请求头（如 Authorization: Bearer xxx 或 api-key: xxx） */
    protected abstract Map<String, String> buildAuthHeaders();

    /** @return API 端点 URL（如 https://api.openai.com/v1/chat/completions） */
    protected abstract String buildApiUrl();

    // ========================================================================
    // Identity
    // ========================================================================

    @Override
    public String getProvider() { return provider; }

    @Override
    public String getModelName() { return modelName; }

    protected MessageFormatter getFormatter() { return formatter; }

    // ========================================================================
    // 公共请求体构建（子类可复用）
    // ========================================================================

    /**
     * 构建标准 OpenAI 兼容的请求体 JSON。
     */
    protected String buildCommonRequestBody(List<Map<String, Object>> messages,
                                            List<ToolSchema> toolSchemas,
                                            GenerateOptions options,
                                            boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("messages", messages);

        if (options != null) {
            if (options.getTemperature() != null) body.put("temperature", options.getTemperature());
            if (options.getMaxTokens() != null) body.put("max_tokens", options.getMaxTokens());
            if (options.getTopP() != null) body.put("top_p", options.getTopP());
        }

        if (toolSchemas != null && !toolSchemas.isEmpty()) {
            body.put("tools", buildToolArray(toolSchemas));
            if (options != null && options.getToolChoice() != null) {
                body.put("tool_choice", convertToolChoice(options.getToolChoice()));
            }
        }

        if (stream) body.put("stream", true);
        return JsonUtils.toCompactJson(body);
    }

    /**
     * 构建工具数组（OpenAI 兼容格式）。
     */
    protected List<Map<String, Object>> buildToolArray(List<ToolSchema> schemas) {
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

    /**
     * 转换工具选择策略为 API 字符串。
     */
    protected String convertToolChoice(ToolChoicePolicy policy) {
        switch (policy) {
            case NONE:     return "none";
            case AUTO:     return "auto";
            case REQUIRED: return "required";
            default:       return "auto";
        }
    }

    // ========================================================================
    // doChat / doStream（统一实现，子类通常无需覆盖）
    // ========================================================================

    protected Mono<ChatResponse> doChat(List<Map<String, Object>> formattedMessages,
                                         List<ToolSchema> toolSchemas,
                                         GenerateOptions options) {
        return Mono.fromCallable(() -> buildCommonRequestBody(formattedMessages, toolSchemas, options, false))
            .flatMap(body -> httpClient.post(buildApiUrl(), buildAuthHeaders(), body))
            .map(this::parseChatResponse);
    }

    protected Flux<ChatStreamChunk> doStream(List<Map<String, Object>> formattedMessages,
                                              List<ToolSchema> toolSchemas,
                                              GenerateOptions options) {
        return Mono.fromCallable(() -> buildCommonRequestBody(formattedMessages, toolSchemas, options, true))
            .flatMapMany(body -> httpClient.postStream(buildApiUrl(), buildAuthHeaders(), body))
            .transform(sseParser::parse);
    }

    // ========================================================================
    // 公共响应解析
    // ========================================================================

    /**
     * 解析聊天响应 JSON（OpenAI 兼容格式）。
     * 子类可覆盖以处理非标准格式。
     */
    @SuppressWarnings("unchecked")
    protected ChatResponse parseChatResponse(String json) {
        Map<String, Object> map = JsonUtils.fromJson(json, Map.class);
        List<Map<String, Object>> choices = (List<Map<String, Object>>) map.get("choices");
        Map<String, Object> choice = choices != null && !choices.isEmpty()
            ? choices.get(0) : Collections.emptyMap();
        Map<String, Object> message = (Map<String, Object>) choice.get("message");

        String content = message != null ? (String) message.get("content") : "";
        String finishReason = (String) choice.get("finish_reason");

        MsgBuilder builder = AssistantMessage.builder()
            .addText(content != null ? content : "");

        if (message != null) {
            parseToolCalls(builder, message);
        }

        Map<String, Object> usage = (Map<String, Object>) map.get("usage");
        int pt = 0, ct = 0;
        if (usage != null) {
            pt = ((Number) usage.getOrDefault("prompt_tokens", 0)).intValue();
            ct = ((Number) usage.getOrDefault("completion_tokens", 0)).intValue();
        }

        return new ChatResponse(builder.build(), new ChatUsage(pt, ct),
            finishReason, modelName);
    }

    /**
     * 解析工具调用（OpenAI 兼容格式）。
     * 子类可覆盖以处理非标准格式。
     */
    @SuppressWarnings("unchecked")
    protected void parseToolCalls(MsgBuilder builder, Map<String, Object> message) {
        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) message.get("tool_calls");
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

    // ========================================================================
    // ChatModel 公共入口
    // ========================================================================

    @Override
    public Mono<ChatResponse> chat(List<Msg> messages, GenerateOptions options) {
        return chatWithTools(messages, Collections.emptyList(), options);
    }

    @Override
    public Flux<ChatStreamChunk> stream(List<Msg> messages, GenerateOptions options) {
        return streamWithTools(messages, Collections.emptyList(), options);
    }

    @Override
    public Flux<ChatStreamChunk> streamWithTools(List<Msg> messages,
                                                  List<ToolSchema> toolSchemas,
                                                  GenerateOptions options) {
        if (!supportsStreaming()) {
            return Flux.error(new ModelCallException(provider, modelName,
                "模型 [" + provider + ":" + modelName + "] 不支持流式调用"));
        }
        List<Map<String, Object>> formatted = formatter.format(messages);
        GenerateOptions opts = options != null ? options : GenerateOptions.defaults();
        return doStream(formatted, toolSchemas, opts);
    }

    @Override
    public Mono<ChatResponse> chatWithTools(List<Msg> messages,
                                             List<ToolSchema> toolSchemas,
                                             GenerateOptions options) {
        List<Map<String, Object>> formatted = formatter.format(messages);
        GenerateOptions opts = options != null ? options : GenerateOptions.defaults();
        return doChatWithRetry(formatted, toolSchemas, opts, 0);
    }

    // ========================================================================
    // 重试
    // ========================================================================

    private Mono<ChatResponse> doChatWithRetry(List<Map<String, Object>> formatted,
                                                List<ToolSchema> toolSchemas,
                                                GenerateOptions options,
                                                int retryCount) {
        return doChat(formatted, toolSchemas, options)
            .onErrorResume(e -> {
                if (retryCount < maxRetries && isRetryable(e)) {
                    return Mono.delay(java.time.Duration.ofMillis(retryDelayMs * (retryCount + 1)))
                        .then(doChatWithRetry(formatted, toolSchemas, options, retryCount + 1));
                }
                return Mono.error(wrapException(e));
            });
    }

    protected boolean isRetryable(Throwable e) {
        if (e instanceof ModelException) {
            int status = ((ModelException) e).getHttpStatus();
            return status == 429 || status >= 500;
        }
        return false;
    }

    protected ModelCallException wrapException(Throwable e) {
        if (e instanceof ModelCallException) return (ModelCallException) e;
        if (e instanceof ModelException me) {
            return new ModelCallException(me.getProvider(), me.getModelName(),
                me.getHttpStatus(), me.getMessage(), e);
        }
        return new ModelCallException(provider, modelName, e.getMessage(), e);
    }

    // ========================================================================
    // 配置
    // ========================================================================

    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public void setRetryDelayMs(long retryDelayMs) { this.retryDelayMs = retryDelayMs; }
}
