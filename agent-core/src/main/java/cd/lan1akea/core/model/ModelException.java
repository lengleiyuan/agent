package cd.lan1akea.core.model;

/**
 * 模型层特定异常，继承自 core.exception.ModelCallException。
 * 在 model 包内使用，由 ChatModelBase 子类抛出。
 */
public class ModelException extends RuntimeException {

    /**
     * AI 提供商名称
     */
    private final String provider;
    /**
     * 模型名称
     */
    private final String modelName;
    /**
     * HTTP 状态码（不适用时为 0）
     */
    private final int httpStatus;

    /**
     * 创建模型异常（含提供商、模型、HTTP 状态码和消息）。
     *
     * @param provider   提供商名称
     * @param modelName  模型名称
     * @param httpStatus HTTP 状态码
     * @param message    详细信息
     */
    public ModelException(String provider, String modelName, int httpStatus, String message) {
        super(message);
        this.provider = provider;
        this.modelName = modelName;
        this.httpStatus = httpStatus;
    }

    /**
     * 创建模型异常（含提供商、模型、消息和根因）。
     *
     * @param provider  提供商名称
     * @param modelName 模型名称
     * @param message   详细信息
     * @param cause     根因
     */
    public ModelException(String provider, String modelName, String message, Throwable cause) {
        super(message, cause);
        this.provider = provider;
        this.modelName = modelName;
        this.httpStatus = 0;
    }

    /**
     * @return 提供商
     */
    public String getProvider() { return provider; }

    /**
     * @return 模型名
     */
    public String getModelName() { return modelName; }

    /**
     * @return HTTP状态码
     */
    public int getHttpStatus() { return httpStatus; }
}
