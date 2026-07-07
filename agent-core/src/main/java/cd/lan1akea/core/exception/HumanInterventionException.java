package cd.lan1akea.core.exception;

import cd.lan1akea.core.tool.ToolCallContext;

/**
 * 人工介入异常。工具审批、参数澄清、业务暂停的统一入口。
 * 所有介入暂停循环（不写错误消息到对话历史），人工解决后从 checkpoint 恢复续跑。
 *
 * <p>使用场景：
 * <ul>
 *   <li>TOOL_APPROVAL — 工具调用前需要人工审批</li>
 *   <li>TOOL_CLARIFY — 工具参数需要人工澄清/修正</li>
 * </ul>
 */
public class HumanInterventionException extends RuntimeException {

    /**
     * 介入类型枚举。
     */
    public enum Type {
        /** 工具审批：人工 approve/deny 后原参数重放 */
        TOOL_APPROVAL,
        /** 工具澄清：人工修正参数后重放 */
        TOOL_CLARIFY
    }

    /** 介入类型 */
    private final Type type;
    /** 暂停原因/问题描述 */
    private final String reason;
    /** 是否可恢复（true=等待人工解决后可恢复，false=直接中断） */
    private final boolean resumable;
    /** 被暂停的工具名称（仅 TOOL_APPROVAL/TOOL_CLARIFY 时有效） */
    private final String toolName;
    /** 调用参数上下文（TOOL_APPROVAL/TOOL_CLARIFY 时携带原参数） */
    private final ToolCallContext callParam;
    /** 审批 TTL（分钟），-1 表示使用默认值 */
    private int ttlMinutes = -1;

    /**
     * 构造人工介入异常。
     */
    private HumanInterventionException(Type type, String reason, boolean resumable,
                                        String toolName, ToolCallContext callParam) {
        super(reason);
        this.type = type;
        this.reason = reason;
        this.resumable = resumable;
        this.toolName = toolName;
        this.callParam = callParam;
    }

    /** 设置审批过期时间（分钟），工具按风险等级自行决定 */
    public HumanInterventionException withTtlMinutes(int minutes) {
        this.ttlMinutes = Math.max(0, minutes);
        return this;
    }

    /** @return 审批 TTL（分钟），-1 表示使用默认值 */
    public int getTtlMinutes() { return ttlMinutes; }

    /**
     * 创建工具审批介入。人工 approve/deny 后可恢复。
     *
     * @param toolName  需要审批的工具名称
     * @param question  审批问题描述
     * @param callParam 调用参数上下文
     * @return HumanInterventionException 实例
     */
    public static HumanInterventionException approval(String toolName, String question,
                                                       ToolCallContext callParam) {
        return new HumanInterventionException(Type.TOOL_APPROVAL, question, true, toolName, callParam);
    }

    /**
     * 创建工具澄清介入。人工修正参数后可恢复。
     *
     * @param toolName  需要澄清的工具名称
     * @param question  澄清问题描述
     * @param callParam 调用参数上下文
     * @return HumanInterventionException 实例
     */
    public static HumanInterventionException clarify(String toolName, String question,
                                                      ToolCallContext callParam) {
        return new HumanInterventionException(Type.TOOL_CLARIFY, question, true, toolName, callParam);
    }

    /**
     * @return 介入类型
     */
    public Type getType() { return type; }

    /**
     * @return 暂停原因/问题描述
     */
    public String getReason() { return reason; }

    /**
     * @return 是否可恢复
     */
    public boolean isResumable() { return resumable; }

    /**
     * @return 被暂停的工具名称（可能为 null）
     */
    public String getToolName() { return toolName; }

    /**
     * @return 调用参数上下文（可能为 null）
     */
    public ToolCallContext getCallParam() { return callParam; }
}
