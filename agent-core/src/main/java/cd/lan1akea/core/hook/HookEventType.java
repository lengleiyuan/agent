package cd.lan1akea.core.hook;

/**
 * Hook 事件类型枚举。
 * 覆盖 Agent 执行生命周期的所有可干预点。
 */
public enum HookEventType {

    /**
     * LLM 推理前（ContextCompression/MemoryEnrichment）
     */
    PRE_REASONING,

    /**
     * LLM 推理后（ContentFilter）
     */
    POST_REASONING,

    /**
     * 模型 API 调用前（Token 计费/缓存）
     */
    PRE_MODEL_CALL,

    /**
     * 模型 API 调用后
     */
    POST_MODEL_CALL,

    /**
     * 单工具调用前（Audit/RateLimit/Permission/ToolAccess）
     */
    PRE_TOOL_CALL,

    /**
     * 单工具调用后（Audit）
     */
    POST_TOOL_CALL,

    /**
     * 错误时
     */
    ON_ERROR,

    /**
     * 人工干预
     */
    ON_INTERRUPT,

    /**
     * 单次 ReAct 迭代后（持久化、检查点等系统级 Hook）
     */
    AFTER_ITERATION,

    /**
     * 达到最大迭代次数，进入总结阶段前。
     * Hook 可注入自定义提示词或设置 bypassMessage 跳过模型总结。
     */
    PRE_SUMMARIZE
}
