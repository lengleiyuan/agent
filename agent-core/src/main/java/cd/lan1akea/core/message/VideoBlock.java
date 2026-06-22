package cd.lan1akea.core.message;

/**
 * 视频内容块。
 */
public class VideoBlock extends ContentBlock {

    /** 视频来源 */
    private final Source source;

    public VideoBlock(Source source) {
        super(TYPE_VIDEO);
        this.source = source;
    }

    /** @return 视频来源 */
    public Source getSource() { return source; }
}
