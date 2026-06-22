package cd.lan1akea.core.message;

/**
 * 文本内容块。
 */
public class TextBlock extends ContentBlock {

    /** 文本内容 */
    private final String text;

    public TextBlock(String text) {
        super(TYPE_TEXT);
        this.text = text;
    }

    /** @return 文本内容 */
    public String getText() {
        return text;
    }
}
