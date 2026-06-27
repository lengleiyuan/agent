package cd.lan1akea.core.model.openai;

import cd.lan1akea.core.formatter.OpenAiMessageFormatter;
import cd.lan1akea.core.model.*;

import java.util.*;

/**
 * OpenAI 聊天模型实现。
 */
public class OpenAIChatModel extends ChatModelBase {

    /**
     * OpenAI API 密钥。
     */
    private final String apiKey;
    /**
     * OpenAI API 基础地址。
     */
    private final String baseUrl;

    /**
     * 使用 API 密钥和模型名称创建实例，使用默认基础地址。
     *
     * @param apiKey    OpenAI API 密钥
     * @param modelName 模型名称
     */
    public OpenAIChatModel(String apiKey, String modelName) {
        this(apiKey, modelName, "https://api.openai.com/v1");
    }

    /**
     * 使用 API 密钥、模型名称和自定义基础地址创建实例。
     *
     * @param apiKey    OpenAI API 密钥
     * @param modelName  模型名称
     * @param baseUrl    自定义 API 基础地址
     */
    public OpenAIChatModel(String apiKey, String modelName, String baseUrl) {
        super("openai", modelName, new OpenAiMessageFormatter());
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
    }

    /**
     * 构造包含 Bearer token 的认证请求头。
     */
    @Override
    protected Map<String, String> buildAuthHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        return headers;
    }

    /**
     * 构造 OpenAI Chat API 的请求 URL。
     */
    @Override
    protected String buildApiUrl() {
        return baseUrl + "/chat/completions";
    }
}
