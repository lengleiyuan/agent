package cd.lan1akea.core.model;

/**
 * LLM API 端点类型。
 */
public enum EndpointType {

    /** 聊天补全端点 */
    CHAT("chat/completions"),

    /** 嵌入端点 */
    EMBEDDING("embeddings"),

    /** 自定义端点 */
    CUSTOM("custom");

    private final String defaultPath;

    EndpointType(String defaultPath) {
        this.defaultPath = defaultPath;
    }

    /** @return 默认路径后缀 */
    public String getDefaultPath() {
        return defaultPath;
    }
}
