package cd.lan1akea.core.message;

/**
 * 思考过程内容块。
 * <p>
 * 部分模型（如 Claude、DeepSeek-R1）在输出最终回复前会产出思考内容。
 * 此块用于承载这些思考内容。
 * </p>
 */
public class ThinkingBlock extends ContentBlock {

    /** 思考文本 */
    private final String thinking;

    public ThinkingBlock(String thinking) {
        super(TYPE_THINKING);
        this.thinking = thinking;
    }

    /** @return 思考文本 */
    public String getThinking() {
        return thinking;
    }
}
