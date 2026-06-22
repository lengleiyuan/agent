package cd.lan1akea.core.exception;

/**
 * 模型调用异常，在调用 LLM API 失败时抛出。
 */
public class ModelCallException extends AgentException {

    private final String provider;
    private final String modelName;
    private final int httpStatus;

    public ModelCallException(String provider, String modelName, int httpStatus, String message) {
        super("MDL_001", buildMessage(provider, modelName, httpStatus, message));
        this.provider = provider;
        this.modelName = modelName;
        this.httpStatus = httpStatus;
    }

    public ModelCallException(String provider, String modelName, String message) {
        this(provider, modelName, 0, message);
    }

    public ModelCallException(String provider, String modelName, String message, Throwable cause) {
        super("MDL_001", buildMessage(provider, modelName, 0, message), cause);
        this.provider = provider;
        this.modelName = modelName;
        this.httpStatus = 0;
    }

    public ModelCallException(String provider, String modelName, int httpStatus, String message, Throwable cause) {
        super("MDL_001", buildMessage(provider, modelName, httpStatus, message), cause);
        this.provider = provider;
        this.modelName = modelName;
        this.httpStatus = httpStatus;
    }

    private static String buildMessage(String provider, String modelName, int httpStatus, String message) {
        StringBuilder sb = new StringBuilder();
        sb.append("模型调用失败 [provider=").append(provider)
            .append(", model=").append(modelName);
        if (httpStatus > 0) {
            sb.append(", httpStatus=").append(httpStatus);
        }
        sb.append("]: ").append(message);
        return sb.toString();
    }

    /** @return 提供商名称 */
    public String getProvider() { return provider; }

    /** @return 模型名称 */
    public String getModelName() { return modelName; }

    /** @return HTTP状态码，0表示非HTTP错误 */
    public int getHttpStatus() { return httpStatus; }
}
