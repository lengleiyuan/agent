package cd.lan1akea.core.hook;

/**
 * Hook 事件类型枚举。
 *
 * <p>覆盖 Agent 执行生命周期的所有可干预点，从 10 个精简为 8 个：
 * PRE_MODEL_CALL / POST_MODEL_CALL 无订阅者，已删除。
 */
public enum HookEventType {

    /** LLM 推理前（ContextCompression / MemoryEnrichment / KB bypass） */
    PRE_REASONING,

    /** LLM 推理后（ContentFilter） */
    POST_REASONING,

    /** 工具调用前（Audit / RateLimit / Permission / ToolAccess） */
    PRE_TOOL_CALL,

    /** 工具调用后（Audit） */
    POST_TOOL_CALL,

    /** 错误时 */
    ON_ERROR,

    /** 人工干预 */
    ON_INTERRUPT,

    /** 单次 ReAct 迭代后（SessionPersistence） */
    AFTER_ITERATION,

    /** 达到最大迭代，进入总结阶段前（可注入提示词或设置 bypassMessage） */
    PRE_SUMMARIZE,

    /** 模型响应组装完成后（含完整 ChatResponse）。
     * 内置 TokenEstimationHook 处理写入 ctx + token 估算。 */
    POST_MODEL
}
