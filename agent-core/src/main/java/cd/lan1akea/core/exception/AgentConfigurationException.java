package cd.lan1akea.core.exception;

/**
 * Agent 配置异常，在 Agent 构建阶段参数不合法时抛出。
 */
public class AgentConfigurationException extends AgentException {

    public AgentConfigurationException(String message) {
        super("CFG_001", message);
    }

    public AgentConfigurationException(String message, Throwable cause) {
        super("CFG_001", message, cause);
    }
}
