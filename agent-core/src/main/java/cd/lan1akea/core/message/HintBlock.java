package cd.lan1akea.core.message;

/**
 * 提示内容块。
 * <p>
 * 用于向 Agent 注入额外的上下文提示，不出现在最终展示中。
 * </p>
 */
public class HintBlock extends ContentBlock {

    /** 提示文本 */
    private final String hint;

    public HintBlock(String hint) {
        super(TYPE_HINT);
        this.hint = hint;
    }

    /** @return 提示文本 */
    public String getHint() {
        return hint;
    }
}
