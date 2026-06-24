package cd.lan1akea.core.hook;

/**
 * Hook 事件类型枚举。
 * <p>
 * 覆盖 Agent 执行生命周期的所有可干预点。
 * </p>
 */
public enum HookEventType {

    /** LLM 推理开始前 */
    PRE_REASONING,

    /** LLM 推理完成后 */
    POST_REASONING,

    /** 行动执行（工具调用汇总）前 */
    PRE_ACTING,

    /** 行动执行完成后 */
    POST_ACTING,

    /** 单个工具调用前 */
    PRE_TOOL_CALL,

    /** 单个工具调用后 */
    POST_TOOL_CALL,

    /** 发生错误时 */
    ON_ERROR,

    /** 人工干预触发 */
    ON_INTERRUPT,

    /** 流式输出每个 chunk 时 */
    ON_STREAM_CHUNK,

    /** 会话摘要生成时 */
    ON_SUMMARY,

    /** 单次调用开始前（最外层，早于所有其他事件） */
    PRE_CALL,

    /** 单次调用完成后（最外层，晚于所有其他事件） */
    POST_CALL
}
