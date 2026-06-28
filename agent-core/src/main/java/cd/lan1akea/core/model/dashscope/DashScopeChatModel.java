package cd.lan1akea.core.model.dashscope;

import cd.lan1akea.core.formatter.OpenAiMessageFormatter;
import cd.lan1akea.core.model.ChatModelBase;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 阿里 DashScope 聊天模型（OpenAI 兼容协议）。
 */
public class DashScopeChatModel extends ChatModelBase {

    private final String apiKey;
    private final String baseUrl;

    public DashScopeChatModel(String apiKey, String modelName) {
        this(apiKey, modelName, "https://dashscope.aliyuncs.com/compatible-mode/v1");
    }

    public DashScopeChatModel(String apiKey, String modelName, String baseUrl) {
        super("dashscope", modelName, new OpenAiMessageFormatter());
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
