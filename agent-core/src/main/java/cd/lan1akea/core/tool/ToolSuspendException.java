package cd.lan1akea.core.tool;

/**
 * 工具暂停异常。
 * 当工具需要人工审批时抛出。区别于普通执行错误，此异常会触发审批中断流程。
 * <p>
 * bypassKey 用于 ApprovalStore 预检：若已批准则框架自动重试工具执行。
 */
public class ToolSuspendException extends RuntimeException {

    private final String question;
    private final String[] options;
    private final String bypassKey;

    public ToolSuspendException(String toolName, String question) {
        this(toolName, question, toolName);
    }

    /**
     * @param toolName  工具名称
     * @param question  审批问题描述
     * @param bypassKey ApprovalStore 预检键（默认 toolName）
     */
    public ToolSuspendException(String toolName, String question, String bypassKey) {
        super("工具 [" + toolName + "] 需要人工决策: " + question);
        this.question = question;
        this.options = new String[]{"批准", "拒绝"};
        this.bypassKey = bypassKey;
    }

    public ToolSuspendException(String toolName, String question, String[] options) {
        super("工具 [" + toolName + "] 需要人工决策: " + question);
        this.question = question;
        this.options = options;
        this.bypassKey = toolName;
    }

    public String getQuestion() { return question; }
    public String[] getOptions() { return options; }
    public String getBypassKey() { return bypassKey; }
}
