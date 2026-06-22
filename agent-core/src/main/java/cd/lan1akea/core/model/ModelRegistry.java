package cd.lan1akea.core.model;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型注册表。
 * <p>
 * 管理所有已注册的模型实例，按 provider:modelName 键索引。
 * 支持运行时动态注册。
 * </p>
 */
public class ModelRegistry {

    /** 键格式: "provider:modelName" */
    private final Map<String, ChatModel> models = new ConcurrentHashMap<>();

    /**
     * 注册模型。
     *
     * @param model 聊天模型实例
     */
    public void register(ChatModel model) {
        String key = buildKey(model.getProvider(), model.getModelName());
        models.put(key, model);
    }

    /**
     * 按提供商和模型名查找模型。
     *
     * @param provider  提供商名称
     * @param modelName 模型名称
     * @return ChatModel 实例，未找到返回 null
     */
    public ChatModel get(String provider, String modelName) {
        return models.get(buildKey(provider, modelName));
    }

    /**
     * 按提供商名查找其默认模型。
     *
     * @param provider 提供商名称
     * @return ChatModel 实例，未找到返回 null
     */
    public ChatModel getByProvider(String provider) {
        return models.entrySet().stream()
            .filter(e -> e.getKey().startsWith(provider + ":"))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(null);
    }

    /**
     * 注销模型。
     *
     * @param provider  提供商名称
     * @param modelName 模型名称
     */
    public void unregister(String provider, String modelName) {
        models.remove(buildKey(provider, modelName));
    }

    /** @return 已注册模型数量 */
    public int size() { return models.size(); }

    private String buildKey(String provider, String modelName) {
        return provider + ":" + modelName;
    }
}
