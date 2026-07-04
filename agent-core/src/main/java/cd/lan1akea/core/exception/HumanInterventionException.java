package cd.lan1akea.core.exception;

import cd.lan1akea.core.tool.ToolCallContext;

/**
 * 人工介入异常。工具审批、参数澄清、业务暂停的统一入口。
 * 所有介入暂停循环（不写错误消息到对话历史），人工解决后从 checkpoint 恢复续跑。
 */
public class HumanInterventionException extends RuntimeException {

    public enum Type {
        TOOL_APPROVAL,   // 工具审批：人工 approve/deny 后原参数重放
        TOOL_CLARIFY,    // 工具澄清：人工修正参数后重放
        BUSINESS_PAUSE   // 业务暂停：人工注入反馈消息续跑
    }

    private final Type type;
    private final String reason;
    private final boolean resumable;
    private final String toolName;
    private final ToolCallContext callParam;

    private HumanInterventionException(Type type, String reason, boolean resumable,
                                        String toolName, ToolCallContext callParam) {
        super(reason);
        this.type = type;
        this.reason = reason;
        this.resumable = resumable;
        this.toolName = toolName;
        this.callParam = callParam;
    }

    public static HumanInterventionException approval(String toolName, String question,
                                                       ToolCallContext callParam) {
        return new HumanInterventionException(Type.TOOL_APPROVAL, question, true, toolName, callParam);
    }

    public static HumanInterventionException clarify(String toolName, String question,
                                                      ToolCallContext callParam) {
        return new HumanInterventionException(Type.TOOL_CLARIFY, question, true, toolName, callParam);
    }

    public static HumanInterventionException pause(String reason) {
        return new HumanInterventionException(Type.BUSINESS_PAUSE, reason, true, null, null);
    }

    public static HumanInterventionException abort(String reason) {
        return new HumanInterventionException(Type.BUSINESS_PAUSE, reason, false, null, null);
    }

    public Type getType() { return type; }
    public String getReason() { return reason; }
    public boolean isResumable() { return resumable; }
    public String getToolName() { return toolName; }
    public ToolCallContext getCallParam() { return callParam; }
}
