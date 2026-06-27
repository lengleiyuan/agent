package cd.lan1akea.core.model.deepseek;

import cd.lan1akea.core.formatter.DeepSeekMessageFormatter;
import cd.lan1akea.core.model.*;

import java.util.*;

/**
 * DeepSeek 聊天模型实现（OpenAI 兼容）。
 */
public class DeepSeekChatModel extends ChatModelBase {

    /**
     * DeepSeek API 密钥。
     */
    private final String apiKey;
    /**
     * DeepSeek API 基础地址。
     */
    private final String baseUrl;

    /**
     * 使用 API 密钥和模型名称创建实例，使用默认基础地址。
     *
     * @param apiKey   DeepSeek API 密钥
     * @param modelName 模型名称
     */
    public DeepSeekChatModel(String apiKey, String modelName) {
        this(apiKey, modelName, "https://api.deepseek.com/v1");
    }

    /**
     * 使用 API 密钥、模型名称和自定义基础地址创建实例。
     *
     * @param apiKey    DeepSeek API 密钥
     * @param modelName  模型名称
     * @param baseUrl    自定义 API 基础地址
     */
    public DeepSeekChatModel(String apiKey, String modelName, String baseUrl) {
        super("deepseek", modelName, new DeepSeekMessageFormatter());
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
     * 构造 DeepSeek Chat API 的请求 URL。
     */
    @Override
    protected String buildApiUrl() {
        return baseUrl + "/chat/completions";
    }
}
