package cd.lan1akea.core.exception;

/**
 * 人工介入异常。
 * 统一替代 HookAbortException、ToolSuspendException，以及 Hook 的 INTERRUPT/ABORT 信号。
 *
 * <p>三种创建方式：</p>
 * <pre>{@code
 * // 业务中断（暂停等人工反馈）
 * throw HumanInterventionException.pause("LLM 输出不完整");
 *
 * // 业务拒绝（直接终止）
 * throw HumanInterventionException.abort("检测到违规内容");
 *
 * // 工具审批
 * throw HumanInterventionException.approval("delete_file", "确认删除 /etc/hosts？");
 * }</pre>
 *
 * <p>ReActLoop 在 .onErrorResume 统一 catch：resumable=true → 中断暂停 → 人工反馈续跑；
 * resumable=false → 直接终止 → 异常传播给调用方。</p>
 */
public class HumanInterventionException extends RuntimeException {

    /**
     * 介入类型。
     */
    public enum Type {
        /** 工具审批 */
        TOOL_APPROVAL,
        /** 业务介入 */
        BUSINESS
    }

    /**
     * 介入类型。
     */
    private final Type type;
    /**
     * 介入原因。
     */
    private final String reason;
    /**
     * 是否可恢复（true=暂停续跑，false=直接终止）。
     */
    private final boolean resumable;
    /**
     * 触发工具名（TOOL_APPROVAL 时有效）。
     */
    private final String toolName;

    private HumanInterventionException(Type type, String reason, boolean resumable, String toolName) {
        super(reason);
        this.type = type;
        this.reason = reason;
        this.resumable = resumable;
        this.toolName = toolName;
    }

    /**
     * 创建可恢复的中断（暂停→人工反馈→续跑）。
     */
    public static HumanInterventionException pause(String reason) {
        return new HumanInterventionException(Type.BUSINESS, reason, true, null);
    }

    /**
     * 创建不可恢复的中断（直接终止）。
     */
    public static HumanInterventionException abort(String reason) {
        return new HumanInterventionException(Type.BUSINESS, reason, false, null);
    }

    /**
     * 创建工具审批中断。
     */
    public static HumanInterventionException approval(String toolName, String question) {
        return new HumanInterventionException(Type.TOOL_APPROVAL, question, true, toolName);
    }

    /**
     * @return 介入类型
     */
    public Type getType() { return type; }

    /**
     * @return 介入原因
     */
    public String getReason() { return reason; }

    /**
     * @return 是否可恢复
     */
    public boolean isResumable() { return resumable; }

    /**
     * @return 触发工具名（TOOL_APPROVAL 时有效）
     */
    public String getToolName() { return toolName; }
}
