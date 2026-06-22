package cd.lan1akea.core.message;

/**
 * 音频内容块。
 */
public class AudioBlock extends ContentBlock {

    /** 音频来源 */
    private final Source source;

    public AudioBlock(Source source) {
        super(TYPE_AUDIO);
        this.source = source;
    }

    /** @return 音频来源 */
    public Source getSource() { return source; }
}
