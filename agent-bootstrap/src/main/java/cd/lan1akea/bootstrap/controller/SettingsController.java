package cd.lan1akea.bootstrap.controller;

import cd.lan1akea.bootstrap.config.DevAgentConfig;
import cd.lan1akea.core.model.ChatModel;
import cd.lan1akea.core.model.DynamicChatModel;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final DynamicChatModel dynamicModel;

    public SettingsController(DynamicChatModel dynamicModel) {
        this.dynamicModel = dynamicModel;
    }

    @GetMapping
    public Mono<Map<String, Object>> get() {
        var m = dynamicModel.getDelegate();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("provider", m.getProvider());
        s.put("model", m.getModelName());
        s.put("apiKey", maskApiKey());
        s.put("maxInputTokens", m.getMaxInputTokens());
        s.put("supportsStreaming", m.supportsStreaming());
        s.put("supportsToolCalling", m.supportsToolCalling());
        return Mono.just(s);
    }

    @PostMapping
    public Mono<Map<String, Object>> update(@RequestBody Map<String, Object> body) {
        String provider = (String) body.getOrDefault("provider", System.getProperty("agent.api.provider", "deepseek"));
        String modelName = (String) body.getOrDefault("model", System.getProperty("agent.api.model", "deepseek-v4-flash"));
        String apiKey = (String) body.get("apiKey");
        String baseUrl = (String) body.get("baseUrl");

        if (apiKey != null && !apiKey.isBlank()) System.setProperty("agent.api.key", apiKey);
        else apiKey = System.getProperty("agent.api.key", "");
        if (baseUrl != null && !baseUrl.isBlank()) System.setProperty("agent.api.base-url", baseUrl);

        System.setProperty("agent.api.provider", provider);
        System.setProperty("agent.api.model", modelName);

        // 即时生效：直接构建新模型并热替换
        try {
            ChatModel newModel = DevAgentConfig.buildRealModel(apiKey, provider, modelName, baseUrl);
            dynamicModel.swap(newModel);
        } catch (Exception e) {
            return Mono.just(Map.of("ok", false, "message", "切换失败: " + e.getMessage()));
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("message", "已切换至 " + provider + ":" + modelName);
        r.put("provider", provider);
        r.put("model", modelName);
        r.put("hasApiKey", !apiKey.isBlank());
        return Mono.just(r);
    }

    private String maskApiKey() {
        String key = System.getProperty("agent.api.key", "");
        if (key.isBlank()) return "(未设置)";
        if (key.length() <= 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}
