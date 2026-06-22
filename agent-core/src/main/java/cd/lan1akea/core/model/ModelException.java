package cd.lan1akea.core.model;

/**
 * 模型层特定异常，继承自 core.exception.ModelCallException。
 * <p>
 * 在 model 包内使用，由 ChatModelBase 子类抛出。
 * </p>
 */
public class ModelException extends RuntimeException {

    private final String provider;
    private final String modelName;
    private final int httpStatus;

    public ModelException(String provider, String modelName, int httpStatus, String message) {
        super(message);
        this.provider = provider;
        this.modelName = modelName;
        this.httpStatus = httpStatus;
    }

    public ModelException(String provider, String modelName, String message, Throwable cause) {
        super(message, cause);
        this.provider = provider;
        this.modelName = modelName;
        this.httpStatus = 0;
    }

    /** @return 提供商 */
    public String getProvider() { return provider; }

    /** @return 模型名 */
    public String getModelName() { return modelName; }

    /** @return HTTP状态码 */
    public int getHttpStatus() { return httpStatus; }
}
