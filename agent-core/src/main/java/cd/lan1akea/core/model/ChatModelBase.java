package cd.lan1akea.core.model;

import cd.lan1akea.core.CoreConstants.ApiFormat;
import cd.lan1akea.core.exception.ModelCallException;
import cd.lan1akea.core.formatter.MessageFormatter;
import cd.lan1akea.core.message.Msg;
import cd.lan1akea.core.message.MsgBuilder;
import cd.lan1akea.core.message.AssistantMessage;
import cd.lan1akea.core.model.transport.HttpClientAdapter;
import cd.lan1akea.core.model.transport.ReactorHttpClientAdapter;
import cd.lan1akea.core.model.transport.SseEventParser;
import cd.lan1akea.core.util.ApiRequestUtil;
import cd.lan1akea.core.util.JsonUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * 聊天模型抽象基类。
 * 提供消息格式化、请求构建、工具调用解析、重试等共享逻辑。
 * 子类只需实现 buildAuthHeaders() 和 buildApiUrl()。
 */
public abstract class ChatModelBase implements ChatModel {

    /**
     * AI 提供商名称
     */
    private final String provider;
    /**
     * 模型名称
     */
    private final String modelName;
    /**
     * 消息格式化器
     */
    private final MessageFormatter formatter;

    /**
     * HTTP 客户端，用于 API 通信
     */
    protected final HttpClientAdapter httpClient;

    /**
     * SSE 事件解析器，用于流式响应
     */
    protected final SseEventParser sseParser;

    /**
     * 最大重试次数
     */
    private int maxRetries = 3;
    /**
     * 重试间隔（毫秒）
     */
    private long retryDelayMs = 1000;

    protected ChatModelBase(String provider, String modelName, MessageFormatter formatter) {
        this.provider = provider;
        this.modelName = modelName;
        this.formatter = formatter;
        this.httpClient = new ReactorHttpClientAdapter();
        this.sseParser = new SseEventParser();
    }

    /**
     * @return 认证请求头（如 Authorization: Bearer xxx 或 api-key: xxx）
     */
    protected abstract Map<String, String> buildAuthHeaders();

    /**
     * @return API 端点 URL（如 https://api.openai.com/v1/chat/completions）
     */
    protected abstract String buildApiUrl();


    @Override
    /**
     * @return 提供商名称
     */
    public String getProvider() { return provider; }

    @Override
    /**
     * @return 模型名称
     */
    public String getModelName() { return modelName; }

    /**
     * @return 消息格式化器
     */
    protected MessageFormatter getFormatter() { return formatter; }


    /**
     * 构建标准 OpenAI 兼容的请求体 JSON。
     */
    protected String buildCommonRequestBody(List<Map<String, Object>> messages,
                                            List<ToolSchema> toolSchemas,
                                            GenerateOptions options,
                                            boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put(ApiFormat.MESSAGES, messages);

        if (options != null) {
            if (options.getTemperature() != null) body.put("temperature", options.getTemperature());
            if (options.getMaxTokens() != null) body.put("max_tokens", options.getMaxTokens());
            if (options.getTopP() != null) body.put("top_p", options.getTopP());
        }

        if (toolSchemas != null && !toolSchemas.isEmpty()) {
            body.put(ApiFormat.TOOLS, buildToolArray(toolSchemas));
            if (options != null && options.getToolChoice() != null) {
                body.put(ApiFormat.TOOL_CHOICE, convertToolChoice(options.getToolChoice()));
            }
        }

        if (stream) body.put(ApiFormat.STREAM, true);
        return JsonUtils.toCompactJson(body);
    }

    /**
     * 构建工具数组（OpenAI 兼容格式）。
     */
    protected List<Map<String, Object>> buildToolArray(List<ToolSchema> schemas) {
        return ApiRequestUtil.buildToolArray(schemas);
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

    /**
     * 检查异常是否可重试。
     *
     * @param e 要检查的异常
     * @return 可重试返回 true（429 / 5xx 状态码 / IO 异常 / 超时）
     */
    protected boolean isRetryable(Throwable e) {
        if (e instanceof ModelException) {
            int status = ((ModelException) e).getHttpStatus();
            return status == 429 || status >= 500;
        }
        if (e instanceof java.io.IOException) return true;
        if (e instanceof java.util.concurrent.TimeoutException) return true;
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof java.io.IOException) return true;
            if (cause instanceof java.util.concurrent.TimeoutException) return true;
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * 包装异常为 ModelCallException。
     *
     * @param e 原始异常
     * @return ModelCallException
     */
    protected ModelCallException wrapException(Throwable e) {
        if (e instanceof ModelCallException) return (ModelCallException) e;
        if (e instanceof ModelException me) {
            return new ModelCallException(me.getProvider(), me.getModelName(),
                me.getHttpStatus(), me.getMessage(), e);
        }
        return new ModelCallException(provider, modelName, e.getMessage(), e);
    }


    /**
     * 设置最大重试次数
     */
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    /**
     * 设置重试延迟（毫秒）
     */
    public void setRetryDelayMs(long retryDelayMs) { this.retryDelayMs = retryDelayMs; }
}
