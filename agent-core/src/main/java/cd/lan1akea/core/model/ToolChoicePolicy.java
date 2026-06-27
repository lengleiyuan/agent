package cd.lan1akea.core.model;

/**
 * 工具选择策略。
 */
public enum ToolChoicePolicy {

    /**
     * 自动选择（LLM 自行决定是否调用工具）
     */
    AUTO,

    /**
     * 必须调用工具（LLM 至少调用一个工具）
     */
    REQUIRED,

    /**
     * 不调用工具（纯文本回复）
     */
    NONE,

    /**
     * 指定工具（LLM 必须调用指定名称的工具）
     */
    SPECIFIC
}
