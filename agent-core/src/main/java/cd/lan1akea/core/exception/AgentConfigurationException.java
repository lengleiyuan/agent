package cd.lan1akea.core.exception;

/**
 * Agent 配置异常，在 Agent 构建阶段参数不合法时抛出。
 */
public class AgentConfigurationException extends AgentException {

    /**
     * 使用给定消息创建配置异常。
     *
     * @param message 错误描述
     */
    public AgentConfigurationException(String message) {
        super("CFG_001", message);
    }

    /**
     * 使用给定消息和原因创建配置异常。
     *
     * @param message 错误描述
     * @param cause   根原因
     */
    public AgentConfigurationException(String message, Throwable cause) {
        super("CFG_001", message, cause);
    }
}
