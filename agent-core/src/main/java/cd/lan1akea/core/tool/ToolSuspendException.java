package cd.lan1akea.core.tool;

/**
 * 工具暂停异常。
 * 当工具需要人工决策时抛出此异常。
 * 区别于普通执行错误，此异常会触发 InterruptHook 流程。
 */
public class ToolSuspendException extends RuntimeException {

    /**
     * 需要决策的问题描述
     */
    private final String question;

    /**
     * 可选项（如 [\"批准\", \"拒绝\"]）
     */
    private final String[] options;

    /**
     * 构造工具暂停异常，默认选项为"批准"和"拒绝"。
     *
     * @param toolName 工具名称
     * @param question 需要决策的问题描述
     */
    public ToolSuspendException(String toolName, String question) {
        super("工具 [" + toolName + "] 需要人工决策: " + question);
        this.question = question;
        this.options = new String[]{"批准", "拒绝"};
    }

    /**
     * 构造工具暂停异常，指定可选项。
     *
     * @param toolName 工具名称
     * @param question 需要决策的问题描述
     * @param options  可选项列表
     */
    public ToolSuspendException(String toolName, String question, String[] options) {
        super("工具 [" + toolName + "] 需要人工决策: " + question);
        this.question = question;
        this.options = options;
    }

    /**
     * @return 决策问题
     */
    public String getQuestion() { return question; }

    /**
     * @return 可选项
     */
    public String[] getOptions() { return options; }
}
