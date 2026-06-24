package cd.lan1akea.core.model.openai;

import cd.lan1akea.core.formatter.OpenAiMessageFormatter;
import cd.lan1akea.core.model.*;

import java.util.*;

/**
 * OpenAI 聊天模型实现。
 */
public class OpenAIChatModel extends ChatModelBase {

    private final String apiKey;
    private final String baseUrl;

    public OpenAIChatModel(String apiKey, String modelName) {
        this(apiKey, modelName, "https://api.openai.com/v1");
    }

    public OpenAIChatModel(String apiKey, String modelName, String baseUrl) {
        super("openai", modelName, new OpenAiMessageFormatter());
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
