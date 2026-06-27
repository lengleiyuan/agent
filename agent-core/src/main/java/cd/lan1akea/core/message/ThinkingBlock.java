package cd.lan1akea.core.message;

/**
 * 思考过程内容块。
 * 部分模型（如 Claude、DeepSeek-R1）在输出最终回复前会产出思考内容。
 * 此块用于承载这些思考内容。
 */
public class ThinkingBlock extends ContentBlock {

    /**
     * 思考文本
     */
    private final String thinking;

    /**
     * 创建带有指定思考内容的内容块。
     *
     * @param thinking 思考/推理文本
     */
    public ThinkingBlock(String thinking) {
        super(TYPE_THINKING);
        this.thinking = thinking;
    }

    /**
     * @return 思考文本
     */
    public String getThinking() {
        return thinking;
    }
}
