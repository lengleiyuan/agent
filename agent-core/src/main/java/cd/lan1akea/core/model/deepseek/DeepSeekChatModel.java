package cd.lan1akea.core.model.deepseek;

import cd.lan1akea.core.formatter.DeepSeekMessageFormatter;
import cd.lan1akea.core.model.*;

import java.util.*;

/**
 * DeepSeek 聊天模型实现（OpenAI 兼容）。
 */
public class DeepSeekChatModel extends ChatModelBase {

    private final String apiKey;
    private final String baseUrl;

    public DeepSeekChatModel(String apiKey, String modelName) {
        this(apiKey, modelName, "https://api.deepseek.com/v1");
    }

    public DeepSeekChatModel(String apiKey, String modelName, String baseUrl) {
        super("deepseek", modelName, new DeepSeekMessageFormatter());
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
    }

    @Override
    protected Map<String, String> buildAuthHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        return headers;
    }

    @Override
    protected String buildApiUrl() {
        return baseUrl + "/chat/completions";
    }
}
