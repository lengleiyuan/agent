package cd.lan1akea.core.agent;

/**
 * Agent 事件类型枚举。
 */
public enum AgentEventType {

    /** Agent 创建完成 */
    CREATED,

    /** Agent 开始执行 */
    STARTED,

    /** 推理阶段开始 */
    REASONING_START,

    /** 推理阶段完成 */
    REASONING_END,

    /** 行动阶段开始 */
    ACTING_START,

    /** 行动阶段完成 */
    ACTING_END,

    /** Agent 执行完成 */
    COMPLETED,

    /** Agent 执行出错 */
    ERROR,

    /** Agent 被终止 */
    ABORTED,

    /** 会话摘要生成 */
    SUMMARIZED
}
