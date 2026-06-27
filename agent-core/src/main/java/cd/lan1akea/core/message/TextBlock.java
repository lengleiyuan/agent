package cd.lan1akea.core.message;

/**
 * 文本内容块。
 */
public class TextBlock extends ContentBlock {

    /**
     * 文本内容
     */
    private final String text;

    /**
     * 创建带有指定文本的内容块。
     *
     * @param text 文本内容
     */
    public TextBlock(String text) {
        super(TYPE_TEXT);
        this.text = text;
    }

    /**
     * @return 文本内容
     */
    public String getText() {
        return text;
    }
}
