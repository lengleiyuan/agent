package cd.lan1akea.harness.spi;

import cd.lan1akea.core.model.ChatModel;

/**
 * 模型提供商 SPI。
 * <p>
 * 第三方实现此接口来注册自定义模型提供商。
 * </p>
 */
@FunctionalInterface
public interface ModelProviderSpi {

    /**
     * 创建模型实例。
     *
     * @param apiKey    API Key
     * @param modelName 模型名称
     * @return ChatModel 实例
     */
    ChatModel create(String apiKey, String modelName);

    /** @return 提供商名称 */
    default String getProviderName() {
        return getClass().getSimpleName();
    }
}
