package cd.lan1akea.core.model;

/**
 * Token 用量统计。
 */
public class ChatUsage {

    /** 输入 Token 数 */
    private final int promptTokens;

    /** 输出 Token 数 */
    private final int completionTokens;

    /** 总 Token 数 */
    private final int totalTokens;

    public ChatUsage(int promptTokens, int completionTokens) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = promptTokens + completionTokens;
    }

    public ChatUsage(int promptTokens, int completionTokens, int totalTokens) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
    }

    /** @return 输入Token数 */
    public int getPromptTokens() { return promptTokens; }

    /** @return 输出Token数 */
    public int getCompletionTokens() { return completionTokens; }

    /** @return 总Token数 */
    public int getTotalTokens() { return totalTokens; }

    @Override
    public String toString() {
        return "ChatUsage{prompt=" + promptTokens
            + ", completion=" + completionTokens
            + ", total=" + totalTokens + "}";
    }
}
