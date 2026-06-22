package cd.lan1akea.core.tool;

/**
 * 工具暂停异常。
 * <p>
 * 当工具需要人工决策时抛出此异常。
 * 区别于普通执行错误，此异常会触发 InterruptHook 流程。
 * </p>
 */
public class ToolSuspendException extends RuntimeException {

    /** 需要决策的问题描述 */
    private final String question;

    /** 可选项（如 [\"批准\", \"拒绝\"]） */
    private final String[] options;

    public ToolSuspendException(String toolName, String question) {
        super("工具 [" + toolName + "] 需要人工决策: " + question);
        this.question = question;
        this.options = new String[]{"批准", "拒绝"};
    }

    public ToolSuspendException(String toolName, String question, String[] options) {
        super("工具 [" + toolName + "] 需要人工决策: " + question);
        this.question = question;
        this.options = options;
    }

    /** @return 决策问题 */
    public String getQuestion() { return question; }

    /** @return 可选项 */
    public String[] getOptions() { return options; }
}
